# F08.7.1 / 03: リーグ・ピラミッド ＋ 昇降格移籍

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-05-31
> **関連ドキュメント**:
> - [README.md](./README.md) — 機能概要・トレーサビリティ
> - [F08.7_tournament_league.md](../F08.7_tournament_league.md) — 大会・ディビジョン・昇降格枠・`PromotionService`（同一大会内昇降格）
> - [F01.2 組織・チーム・メンバー・ロール] — 組織階層 `organizations.parent_organization_id` / `OrganizationHierarchyService` / `team_org_memberships`
> - [F04.3 プッシュ通知] / [F04.1 タイムライン] — 降格通知の配信基盤
> - [docs/security/03_role_authority_model.md](../../security/03_role_authority_model.md) — 移籍系 API の認可方針

本書は確定要件 ⑥（昇格＝上位リーグ主催者がプル招待）・⑦（リーグ・ピラミッドを組織階層から導出）・⑧（移籍で通算成績/テンプレート/identity/順位履歴を全部持ち運ぶ）・⑨（降格はプッシュ＝上位主催者が送り出し＋出身県協会へ通知）を具体化する。

---

## 1. 概要

「九州リーグ ⊃ 大分県リーグ ⊃ …」のような**リーグ・ピラミッド**を構成し、シーズン後の昇格（下位 → 上位）・降格（上位 → 下位）を**組織をまたいで**行えるようにする。

- **同一大会内の部間昇降格**（例: 大分県リーグ 2 部 → 1 部）は F08.7 の既存 `PromotionService` で完結（再実装しない）。
- **組織をまたぐ昇降格**（例: 大分県リーグ 1 部 → 九州リーグ）が本書の**唯一の新規実装対象**。

---

## 2. 中核思想 — ピラミッドは組織階層から導出する（要件⑦）

**新規にピラミッド専用テーブル（`league_series` 等）を作らない。** 既存の組織階層から導出する:

- `organizations.parent_organization_id` ＋ `OrganizationHierarchyService`（祖先探索・サイクル検出・最大深度 5）が実装済み。
- 九州協会（parent・`org_type=ASSOCIATION`）⊃ 大分県協会（child・`org_type=ASSOCIATION`）という階層を `parent_organization_id` で表現する（**組織が組織に属している前提**）。
- 「上位リーグ」＝親組織が主催する大会、「下位リーグ」＝子組織が主催する大会。
- 県→地域→全国の多段ピラミッドも `parent_organization_id` のチェーンで自然に表現される。

```
九州協会（ASSOCIATION）           ← 上位リーグ主催者
  └─ 大分県協会（ASSOCIATION）    ← 下位リーグ主催者
       └─ 各チーム（team_org_memberships で所属）
```

### 2.1 責務分離（同一大会内 vs 組織またぎ）

| 種別 | 担当 | 仕組み |
|------|------|--------|
| 同一大会内の部間昇降格（2部↔1部） | 既存 `PromotionService` | `tournament_divisions.promotion_slots/relegation_slots` ＋ `tournament_promotion_records` |
| 組織またぎの昇格（県1部 → 九州リーグ） | **新 `league_transfer`** | プル型招待（§4） |
| 組織またぎの降格（九州リーグ → 県リーグ） | **新 `league_transfer`** | プッシュ型送り出し（§5） |

この線引きを設計書で明確化し、`PromotionService` には組織またぎロジックを混入させない。

---

## 3. データ持ち運びの土台（要件⑧）

移籍で持ち運ぶデータ（通算成績・エントリーテンプレート・チーム identity・順位履歴）は**すべて team_id スコープで蓄積済み**。team_id は移籍しても不変なので、**コピー不要で自動的に付いてくる**。

| データ | 蓄積先 | 持ち運び方式 |
|--------|--------|------------|
| 通算成績 | `tournament-stats`（team_id 集計） | team_id 串刺しで自動表示 |
| エントリーテンプレート | `tournament_entry_templates.team_id` | team_id スコープなので移籍後も使える |
| チーム identity | `teams`（不変） | team_id 不変 |
| 順位履歴 | `tournament-history`（team_id 集計） | team_id 串刺しで自動表示 |

### 3.1 新規テーブル `league_transfer`（tournament ドメイン）

両方向（昇格・降格）を 1 テーブルで担う。**主キーは UUIDv7**（原則 6）。**クロスドメイン FK なし**（原則 1）。

| カラム名 | 型 | NULL | 説明 |
|---------|---|------|------|
| `id` | BINARY(16) | NO | PK（UUIDv7） |
| `direction` | VARCHAR(20) | NO | `PROMOTION` / `RELEGATION` |
| `team_id` | BIGINT UNSIGNED | NO | 移籍対象チーム。FK なし |
| `from_organization_id` | BIGINT UNSIGNED | NO | 移籍元 org（昇格時=下位県協会 / 降格時=上位協会）。FK なし |
| `to_organization_id` | BIGINT UNSIGNED | NO | 移籍先 org（昇格時=上位協会 / 降格時=出身県協会）。FK なし |
| `source_division_id` | BIGINT UNSIGNED | YES | 移籍元ディビジョン。FK なし |
| `target_division_id` | BIGINT UNSIGNED | YES | 移籍先ディビジョン（配属確定時にセット）。FK なし |
| `season` | VARCHAR(20) | NO | シーズン識別子（二重起票抑止キーの一部） |
| `final_rank` | INT | YES | 移籍元での最終順位（昇格枠/降格枠判定の根拠） |
| `status` | VARCHAR(20) | NO | §3.2 の状態 |
| `initiated_by` | BIGINT UNSIGNED | NO | 起票者（上位 org ADMIN）。FK なし |
| `responded_by` | BIGINT UNSIGNED | YES | 応答者（チーム代表 / 下位 org ADMIN）。FK なし |
| `message` | VARCHAR(500) | YES | 招待/送り出しメッセージ |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP |
| `responded_at` | DATETIME | YES | 応答日時 |

**インデックス**

```sql
UNIQUE KEY uq_lt_team_season_direction (team_id, season, direction)  -- 二重起票抑止
INDEX idx_lt_to_org (to_organization_id, status)                     -- 降格受信箱（下位 org が配属待ちを引く）
INDEX idx_lt_team (team_id, status)                                  -- チーム側受信箱
```

> 移籍記録は from / to の 2 組織をまたぐため、単一 organization_id でテナント絞りできない。よって `AbstractTenantAwareRepository` は適用せず、用途別の index（to_org / team）で引く（README §4 の原則 7 補足）。

### 3.2 状態遷移

| direction | 状態遷移 |
|-----------|---------|
| `PROMOTION`（プル型） | `INVITED` → `ACCEPTED` / `DECLINED` / `CANCELLED` |
| `RELEGATION`（プッシュ型） | `DISPATCHED` → `ACKNOWLEDGED` / `PLACED` / `CANCELLED` |

`CANCELLED` は起票者（上位 org ADMIN）が応答前に取り消した場合。

### 3.3 昇格候補のテーブルレス導出

昇格候補一覧は**テーブルを持たない**。完了済みの子組織リーグの昇格枠チームを `PromotionService.getPromotionPreview()` ＋ `OrganizationHierarchyService`（傘下 org 列挙）から**都度導出する読み取りビュー（API）**として提供する（§6 の `GET .../promotion-candidates`）。

---

## 4. 昇格フロー（プル型・要件⑥）

起点は**上位リーグ主催者が検索して招待**する。

1. 九州協会 ADMIN が「昇格候補」一覧を閲覧する。候補＝**傘下の県協会リーグで昇格枠（`promotion_slots`）に入ったチーム**を §3.3 で導出。または**任意のチームを名前で検索**して招待対象にできる。
2. 自分のディビジョン（例: 九州リーグ 1 部）を指定して `league_transfer(direction=PROMOTION, status=INVITED)` を起票＝**招待送信**。`from_organization_id`＝チームの出身県協会、`to_organization_id`＝九州協会、`target_division_id`＝招待先ディビジョン。
3. 招待されたチームの代表（ADMIN / DEPUTY_ADMIN）が承認 → `status=ACCEPTED`、`responded_by` セット → `target_division_id` の `tournament_participant` を新規作成（status=REGISTERED）。
4. 通算成績・テンプレート・identity・順位履歴は team_id 串刺しで**自動表示**（§3・コピー不要）。

辞退時は `status=DECLINED`。チーム側は受信箱（§6 の `GET /teams/{teamId}/league-transfers`）で招待を確認・応答する。

---

## 5. 降格フロー（プッシュ型・要件⑨）

起点は**上位リーグ主催者が送り出し**、出身県協会 org に**通知**する。降格も昇格と対称に上位リーグ主催者が制御する。

### 5.1 基本フロー

1. 九州リーグ大会終了 → 降格枠（`relegation_slots`）チームを `PromotionService.getPromotionPreview()` で判定。
2. 九州協会 ADMIN が「降格送り出し」を実行 → 各チームの**出身県協会**を §5.2 で解決。
3. `league_transfer(direction=RELEGATION, status=DISPATCHED)` を起票し、`to_organization_id`（例: 大分県協会）へ **通知**（既存 F04.3 プッシュ通知 / F04.1 タイムライン）。チーム側受信箱にも降格通知として表示。
4. 大分県協会 ADMIN が次シーズン大会編成時にそのチームを該当ディビジョンへ登録 → `target_division_id` セット・`status=PLACED`。
   - **降格先は次シーズンのため、配属は「通知＋候補化」で受け、実配属は下位 org の次大会作成時**に行う（降格時点で次大会が未作成のケースを吸収）。`DISPATCHED → ACKNOWLEDGED（下位 org が受領）→ PLACED（配属完了）` の二段で吸収する。

### 5.2 出身県協会の解決（要件⑨・送り先の限定）

- チームの出身県協会＝team の ACTIVE `team_org_memberships` のうち、**送り出し元 org（九州協会）の子孫 ASSOCIATION** を `OrganizationHierarchyService` で特定する（`findFirstByTeamIdAndStatus` で所属を引き、子孫判定でフィルタ）。
- これにより**無関係な org へ降格させない**（送り先を子孫 ASSOCIATION に限定）。
- **該当が複数/不明な場合の根治**: 子孫 ASSOCIATION が複数あれば ADMIN に選択を促す。**0 件なら降格送り出しを保留し、ADMIN へ警告を返す**（症状を握りつぶさず、`LeagueTransferOriginNotResolvableException` 等で正直にエラー化＝根治治療）。

---

## 6. API（新設）

| メソッド | パス | 認可 | 説明 |
|---------|-----|------|------|
| GET | `/api/v1/organizations/{orgId}/promotion-candidates` | 上位 org ADMIN | 傘下リーグの昇格枠チームを導出して返す（テーブルレス・§3.3） |
| POST | `/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/league-transfers` | 上位 org ADMIN | 昇格招待。body: `{ teamId, message? }` |
| POST | `/api/v1/organizations/{orgId}/tournaments/{tId}/league-transfers/relegate` | 上位 org ADMIN | 降格送り出し（降格枠チーム一括）。body: `{ teamIds[], message? }` |
| GET | `/api/v1/teams/{teamId}/league-transfers` | 当該チーム MEMBER 以上 | チーム側受信箱（招待・降格通知） |
| POST | `/api/v1/teams/{teamId}/league-transfers/{id}/accept` | 当該チーム ADMIN/DEPUTY | 昇格招待を承認 → `tournament_participant` 作成 |
| POST | `/api/v1/teams/{teamId}/league-transfers/{id}/decline` | 当該チーム ADMIN/DEPUTY | 昇格招待を辞退 |
| GET | `/api/v1/organizations/{orgId}/inbound-relegations` | 下位 org ADMIN | 受け取った降格チーム一覧（配属待ち） |
| POST | `/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/league-transfers/{id}/place` | 下位 org ADMIN | 降格チームを次大会の該当ディビジョンへ配属 → `status=PLACED` |

- **チーム検索**: 任意チーム招待のため、既存チーム検索 API（`/teams/search` 等）を流用可能か実装時に確認し、流用できなければ**最小の検索 EP を新設**する（症状を隠さず実装）。

---

## 7. 認可・セキュリティ

| 操作 | 許可ロール |
|------|-----------|
| 昇格招待送信（`POST .../league-transfers`） | 上位リーグ大会の主催組織 ADMIN / SYSTEM_ADMIN のみ |
| 降格送り出し（`POST .../relegate`） | 同上（上位主催組織 ADMIN） |
| 昇格招待への応答（accept/decline） | **招待された team の ADMIN/DEPUTY のみ**（他チームの招待を横取り不可） |
| 降格チームの配属（place） | **下位（出身県協会）組織 ADMIN のみ** |

- 出身県協会の解決は `OrganizationHierarchyService` の祖先/子孫判定で「送り出し元の子孫 ASSOCIATION」に限定（§5.2・無関係 org への降格を防ぐ）。
- すべて team_id・division_id・organization_id を **ID 参照**（クロスドメイン FK なし・原則 1）。
- **存在しない対象は 404**（IDOR 統一）。
- **二重起票は `UNIQUE(team_id, season, direction)` で抑止**。
- accept で作成する `tournament_participant` は招待 `target_division_id` のものに限定（招待外ディビジョンへの混入防止）。
- 移籍系 API の認可方針は docs/security §（README §B で追記）に集約記載する。

---

## 8. 集計クエリの org 非依存検証（要件⑧の前提）

**検証必須**: `tournament-stats` / `tournament-history` の集計クエリが **organization で絞っていないこと**を実装時に確認する。

- もし organization で絞っていると、別組織のリーグへ昇格した時点で通算成績・順位履歴が**切れて**しまう（要件⑧違反）。
- 絞っている場合は **org 絞りを撤廃**し、team_id 串刺しの集計に修正する（症状を隠さず根治）。
- 撤廃が他機能（組織別レポート等）に影響する場合は、別途「組織別フィルタは任意パラメータ」として切り出し、デフォルトは team_id 全体集計とする。

---

## 9. `PromotionService`（既存・同一大会内）との線引き

- `PromotionService.getPromotionPreview / executePromotions` と `tournament_promotion_records` は**同一大会内の部間移動専用**として維持する（再実装しない）。
- 本書 `league_transfer` は**組織をまたぐ移籍専用**。`PromotionService` に組織またぎロジックを混入させない。
- 昇格候補の導出（§3.3）では `PromotionService.getPromotionPreview` を**読み取りで再利用**する（「どのチームが昇格枠か」の判定ロジックを二重実装しない）。

---

## 10. 精査ログ

### 10.1 1 回目
- **不備**: 昇格プル（§4）＋降格プッシュ（§5）の両方向を網羅。`league_transfer` の状態遷移を direction 別に定義。配属の二段（DISPATCHED→PLACED）で次シーズン未作成を吸収。
- **セキュリティ**: 昇格招待の横取り防止（accept は招待 team ADMIN のみ）、降格の送り先 org 限定（子孫 ASSOCIATION のみ）、二重起票 UNIQUE、404 統一、クロスドメイン FK なし。
- **ユーザビリティ**: 移籍が「簡単」（team_id 串刺しでデータ自動追従・コピー不要）。チーム側受信箱で招待を一覧応答。
- **見落とし**: 通算集計の org 非依存検証（§8）、出身県協会 0 件時の警告（§5.2・症状を隠さない）、PromotionService 流用（§9）。
- **保守性**: 同一大会内 vs 組織またぎの責務分離（§2.1/§9）、UUIDv7（原則 6）、`AbstractTenantAwareRepository` 非適用の理由明記（§3.1）。

### 10.2 未解決事項

**現時点でなし。**
