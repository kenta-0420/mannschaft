# F08.7.1 / 03: リーグ・ピラミッド ＋ 昇降格移籍（プッシュ＋承認の対称モデル）

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-05-31
> **関連ドキュメント**:
> - [README.md](./README.md) — 機能概要・トレーサビリティ
> - [F08.7_tournament_league.md](../F08.7_tournament_league.md) — 大会・ディビジョン・昇降格枠・`PromotionService`（同一大会内昇降格）
> - [F01.2 組織・チーム・メンバー・ロール] — 組織階層 `organizations.parent_organization_id` / `OrganizationHierarchyService` / `team_org_memberships`
> - [F04.3 プッシュ通知] / [F04.1 タイムライン] — 送り出し通知の配信基盤
> - [docs/security/03_role_authority_model.md](../../security/03_role_authority_model.md) §15.3 — 移籍系 API の認可方針

本書は確定要件 ⑥（リーグ・ピラミッドを組織階層から導出）・⑦（移籍で通算成績/テンプレート/identity/順位履歴を全部持ち運ぶ）・⑧（**昇降格はどちらも「プッシュ＋承認」の対称モデル**）を具体化する。

---

## 1. 概要

「九州リーグ ⊃ 大分県リーグ ⊃ …」のような**リーグ・ピラミッド**を構成し、シーズン後の昇格（下位 → 上位）・降格（上位 → 下位）を**組織をまたいで**行えるようにする。

- **同一大会内の部間昇降格**（例: 大分県リーグ 2 部 → 1 部）は F08.7 の既存 `PromotionService` で完結（再実装しない）。
- **組織をまたぐ昇降格**（例: 大分県リーグ 1 部 → 九州リーグ）が本書の**唯一の新規実装対象**。

### 1.1 対称モデルの中核原則 — 「手放す側が送り出し、受け入れる側が承認」

昇格・降格のどちらも、次の **2 段（DISPATCHED → PLACED）** で進む統一フローとする（旧「昇格＝プル型招待」モデルは破棄）:

| 段 | 主体 | 内容 |
|----|------|------|
| **送り出し（DISPATCHED）** | **チームを手放す側 org の主催 ADMIN** | 昇降格枠チームを `league_transfer(status=DISPATCHED)` で起票し、受け入れ側 org へ**通知** |
| **承認・配属（PLACED）** | **チームを受け入れる側 org の主催 ADMIN** | 受け入れ側の大会・ディビジョンを指定して承認 → `tournament_participant` を作成・`status=PLACED` |

| 方向 | 手放す側（送り出し起票） | 受け入れる側（承認・配属） |
|------|------------------------|---------------------------|
| **昇格（PROMOTION）** | 下位 org（例: 大分県協会） | 上位 org（例: 九州協会） |
| **降格（RELEGATION）** | 上位 org（例: 九州協会） | 下位 org（例: 大分県協会） |

> この対称性により、昇格・降格を同一の状態機械・同一の API 形（`promote`/`relegate` で起票、`approve`/`decline`/`cancel` で応答）で扱える。`PromotionService` には組織またぎロジックを混入させない。

---

## 2. 中核思想 — ピラミッドは組織階層から導出する（要件⑥）

**新規にピラミッド専用テーブル（`league_series` 等）を作らない。** 既存の組織階層から導出する:

- `organizations.parent_organization_id` ＋ `OrganizationHierarchyService`（祖先探索・サイクル検出・最大深度 5）が実装済み。

> **🟠 既存サービスへの公開メソッド新設が必要（O-1 訂正・実コード確認済み）**: `OrganizationHierarchyService` の**公開メソッドは `getAncestors(orgId, requesterId)` と `getChildren(orgId, requesterId, cursor, size)` の 2 つだけ**（DTO 返却・ユーザー閲覧権限フィルタ込み）。本書が必要とする「**org → org の祖先/子孫を真偽判定する**」API は**存在しない**。`hasAncestor(startOrgId, targetOrgId)`（純粋な org→org 祖先チェーン判定・サイクル検出・maxDepth 制限つき）は **private**（`OrganizationHierarchyService.java:291`）、`isDescendantMember(requesterId, targetOrgId)` も **private** かつ「**ユーザーの所属**が targetOrg の子孫にあるか」を見るもので（同 :261）org→org 判定ではない。
>
> したがって**「既存サービスでそのまま解決可能」ではなく、新規公開メソッドの新設が必要**。既存の `hasAncestor` のサイクル検出/深度制限ロジックを土台に、以下の真偽判定メソッドを `OrganizationHierarchyService` に**新設**する想定で本書を記述する（実装工数の正直化）:
>
> ```java
> /** ancestorOrgId が descendantOrgId の祖先か（org→org・サイクル/深度制限つき）。既存 private hasAncestor を公開化・整理して実装。 */
> public boolean isAncestorOf(Long ancestorOrgId, Long descendantOrgId);
> /** descendantOrgId が ancestorOrgId の子孫か（isAncestorOf の逆引き）。 */
> public boolean isDescendantOf(Long descendantOrgId, Long ancestorOrgId);
> ```
>
> 本書 §5.2 / §5.3 / §7 の「`OrganizationHierarchyService` で祖先/子孫を判定」は、この**新設公開メソッド**を指す。
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
| 組織またぎの昇格（県1部 → 九州リーグ） | **新 `league_transfer`** | 下位 org が送り出し → 上位 org が承認（§4） |
| 組織またぎの降格（九州リーグ → 県リーグ） | **新 `league_transfer`** | 上位 org が送り出し → 下位 org が承認（§5） |

この線引きを設計書で明確化し、`PromotionService` には組織またぎロジックを混入させない。

---

## 3. データ持ち運びの土台（要件⑦）

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
| `from_organization_id` | BIGINT UNSIGNED | NO | **手放す側** org（昇格時=下位県協会 / 降格時=上位協会）。FK なし |
| `to_organization_id` | BIGINT UNSIGNED | NO | **受け入れる側** org（昇格時=上位協会 / 降格時=出身県協会）。FK なし |
| `source_division_id` | BIGINT UNSIGNED | YES | 移籍元ディビジョン。FK なし |
| `target_division_id` | BIGINT UNSIGNED | YES | 移籍先ディビジョン（承認・配属確定時にセット）。FK なし |
| `season` | VARCHAR(20) | NO | シーズン識別子（二重起票抑止キーの一部） |
| `final_rank` | INT | YES | 移籍元での最終順位（昇格枠/降格枠判定の根拠） |
| `status` | VARCHAR(20) | NO | §3.2 の状態 |
| `initiated_by` | BIGINT UNSIGNED | NO | 起票者（手放す側 org ADMIN）。FK なし |
| `responded_by` | BIGINT UNSIGNED | YES | 応答者（受け入れ側 org ADMIN）。FK なし |
| `message` | VARCHAR(500) | YES | 送り出しメッセージ |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP |
| `responded_at` | DATETIME | YES | 応答日時 |

**インデックス**

```sql
UNIQUE KEY uq_lt_team_season_direction (team_id, season, direction)  -- 二重起票抑止
INDEX idx_lt_to_org (to_organization_id, status)                     -- 受信箱（受け入れ側 org が DISPATCHED を引く）
INDEX idx_lt_from_org (from_organization_id, status)                 -- 送り出し側の進捗一覧
INDEX idx_lt_team (team_id, status)                                  -- チーム側の状況閲覧
```

> 移籍記録は from / to の 2 組織をまたぐため、単一 organization_id でテナント絞りできない。よって `AbstractTenantAwareRepository` は適用せず、用途別の index（from_org / to_org / team）で引く（README §4 の原則 7 補足）。

### 3.2 状態遷移（両方向で共通）

```
DISPATCHED ──┬─ approve ─→ PLACED      （受け入れ側 org が承認・配属）
             ├─ decline ─→ DECLINED    （受け入れ側 org が受け入れ拒否）
             └─ cancel  ─→ CANCELLED   （手放す側 org が応答前に取り消し）
```

| status | 意味 | セットする主体 |
|--------|------|---------------|
| `DISPATCHED` | 送り出し起票済み・受け入れ承認待ち | 手放す側 org ADMIN（起票時） |
| `PLACED` | 受け入れ側で配属完了（`tournament_participant` 作成済み） | 受け入れ側 org ADMIN |
| `DECLINED` | 受け入れ側が拒否 | 受け入れ側 org ADMIN |
| `CANCELLED` | 手放す側が応答前に取り消し | 手放す側 org ADMIN |

> 昇格も降格も**同一の状態機械**を共有する。direction が違うだけで、送り出し・承認・拒否・取消の遷移は完全に対称。

> **Y-4（状態語彙の取り違え防止）**: `league_transfer.status=PLACED` は **transfer（移籍手続き）の状態**であって、participant の状態ではない。承認・配属（PLACED）時に作成する `tournament_participant` は **`status=REGISTERED` で作成する**（`ParticipantStatus` のデフォルト＝`REGISTERED`／値は `REGISTERED`/`ACTIVE`/`WITHDRAWN`/`DISQUALIFIED`、実コード `ParticipantStatus.java:6-10` 確認済み。`PLACED` という participant 状態は存在しない）。両者を混同しないこと。

### 3.3 昇降格候補のテーブルレス導出

候補一覧は**テーブルを持たない**。完了済み大会の昇格枠/降格枠チームを `OrganizationHierarchyService`（親/子孫 org 列挙）＋順位データから**都度導出する読み取りビュー（API）**として提供する（§6 の `GET .../transfer-candidates`）。

> **🟠 `getPromotionPreview` は境界部の枠を返さない（O-2 訂正・実コード確認済み）**: `PromotionService.getPromotionPreview(tournamentId)`（`PromotionService.java:46-99`）は **同一大会内の隣接ディビジョン間** のみを対象とする。具体的には、昇格候補は「上位ディビジョンが存在する（`i > 0`）」場合のみ、降格候補は「下位ディビジョンが存在する（`i < size-1`）」場合のみ生成する。
>
> つまり**最上位ディビジョンの昇格枠（上にディビジョンが無い＝組織またぎ昇格の対象）と、最下位ディビジョンの降格枠（下にディビジョンが無い＝組織またぎ降格の対象）は `getPromotionPreview` の戻り値に含まれない**。これらは本書が扱う**組織またぎ昇降格の対象そのもの**である。
>
> **責務の線引き（訂正）**:
> - **同一大会内の部間昇降格** → `PromotionService.getPromotionPreview` を流用（隣接ディビジョン間。再実装しない）。
> - **組織またぎの昇降格候補（最上位部の昇格枠／最下位部の降格枠）** → `getPromotionPreview` では取得できないため、`league_transfer` 側が **最上位/最下位ディビジョンの `promotion_slots` / `relegation_slots` と最終順位 `tournament_standings` から独自に境界枠を判定**する（最上位部で順位 ≤ `promotion_slots` のチーム＝昇格送り出し候補、最下位部で順位 > `総数 - relegation_slots` のチーム＝降格送り出し候補）。
> - これは `getPromotionPreview` の重複実装ではない（同サービスがカバーしない境界部を補う独立判定）。「二重実装しない」という旧記述は、この境界部判定が別物である点を踏まえて訂正する。`transfer-candidates` API はこの境界部判定ロジックを `league_transfer` ドメイン（または専用 reader）に実装する。

---

## 4. 昇格フロー（プッシュ＋上位承認・要件⑧）

**手放す側＝下位 org（大分県協会）が送り出し、受け入れる側＝上位 org（九州協会）が承認**する。

1. 大分県リーグ大会終了 → 昇格枠（`promotion_slots`）チームを判定。**最上位ディビジョンの昇格枠は `getPromotionPreview` に含まれない**ため（§3.3・O-2）、`league_transfer` 側で最上位部の `promotion_slots` ＋ `tournament_standings` から境界枠チームを独自に算出する。
2. 大分県協会 ADMIN（下位 org）が「昇格送り出し」を実行 → 親 org（九州協会）を `parent_organization_id` で解決。
3. `league_transfer(direction=PROMOTION, status=DISPATCHED)` を起票し、`to_organization_id`（九州協会）へ **通知**（既存 F04.3 プッシュ通知 / F04.1 タイムライン）。`from_organization_id`＝大分県協会、`source_division_id`＝大分県リーグ 1 部、`final_rank` をセット。
4. 九州協会 ADMIN（上位 org）が受信箱（§6 `GET .../inbound-transfers`）で確認し、自分のディビジョン（例: 九州リーグ 1 部）を指定して**承認** → `target_division_id` をセット・`status=PLACED`・`responded_by` セット → `target_division_id` の `tournament_participant` を新規作成（status=REGISTERED）。
5. 通算成績・テンプレート・identity・順位履歴は team_id 串刺しで**自動表示**（§3・コピー不要）。

受け入れ拒否時は上位 org が `status=DECLINED`。送り出し取消は下位 org が応答前に `status=CANCELLED`。

---

## 5. 降格フロー（プッシュ＋下位承認・要件⑧）

**手放す側＝上位 org（九州協会）が送り出し、受け入れる側＝下位 org（大分県協会）が承認・配属**する。昇格と完全対称。

### 5.1 基本フロー

1. 九州リーグ大会終了 → 降格枠（`relegation_slots`）チームを判定。**最下位ディビジョンの降格枠は `getPromotionPreview` に含まれない**ため（§3.3・O-2）、`league_transfer` 側で最下位部の `relegation_slots` ＋ `tournament_standings` から境界枠チームを独自に算出する。
2. 九州協会 ADMIN（上位 org）が「降格送り出し」を実行 → 各チームの**出身県協会**を §5.2 で解決。
3. `league_transfer(direction=RELEGATION, status=DISPATCHED)` を起票し、`to_organization_id`（例: 大分県協会）へ **通知**。チーム側受信箱にも降格通知として表示。`from_organization_id`＝九州協会、`source_division_id`＝九州リーグ、`final_rank` をセット。
4. 大分県協会 ADMIN（下位 org）が次シーズン大会編成時にそのチームを該当ディビジョンへ**承認・配属** → `target_division_id` セット・`status=PLACED`・`tournament_participant` 作成。
   - **降格先は次シーズンのため、配属は「通知＋候補化（DISPATCHED）」で受け、実配属（PLACED）は下位 org の次大会作成時**に行う（降格時点で次大会が未作成のケースを吸収）。受け入れ側が次大会を持つまで `DISPATCHED` のまま受信箱に滞留させる。

### 5.2 出身県協会の解決（送り先の限定）

- チームの出身県協会＝team の ACTIVE `team_org_memberships` のうち、**送り出し元 org（九州協会）の子孫 ASSOCIATION** を `OrganizationHierarchyService` で特定する（`findFirstByTeamIdAndStatus` で所属を引き、子孫判定でフィルタ）。
- これにより**無関係な org へ降格させない**（送り先を子孫 ASSOCIATION に限定）。
- **該当が複数/不明な場合の根治**: 子孫 ASSOCIATION が複数あれば ADMIN に選択を促す。**0 件なら降格送り出しを保留し、ADMIN へ警告を返す**（症状を握りつぶさず、`LeagueTransferTargetNotResolvableException` 等で正直にエラー化＝根治治療）。

### 5.3 昇格側の送り先解決（対称）

- 昇格時の受け入れ先＝送り出し元（下位 org）の **`parent_organization_id` 系列の祖先 org** に限定する（`OrganizationHierarchyService` の祖先探索）。
- 祖先 org が複数段ある場合は「直近の親 org のうち大会を主催している org」を既定とし、ADMIN が明示選択も可能。0 件（親 org なし）なら昇格送り出しを保留し ADMIN へ警告。

---

## 6. API（新設）

`{push}` ＝手放す側 org、`{recv}` ＝受け入れ側 org。

| メソッド | パス | 認可 | 説明 |
|---------|-----|------|------|
| GET | `/api/v1/organizations/{orgId}/tournaments/{tId}/transfer-candidates` | 手放す側 org ADMIN | 当該大会の**境界部**昇格枠（最上位部）/降格枠（最下位部）チームを `tournament_standings` ＋ `promotion_slots`/`relegation_slots` から `league_transfer` 側で独自判定し、組織階層（新設 `isAncestorOf`/`isDescendantOf`）で送り先 org を解決して導出（テーブルレス・§3.3）。`direction` クエリで昇格/降格を切替 |
| POST | `/api/v1/organizations/{orgId}/tournaments/{tId}/league-transfers/promote` | **下位 org ADMIN** | 昇格送り出し。昇格枠チームを上位 org へ DISPATCHED 起票。body: `{ teamIds[], targetOrganizationId?, message? }` |
| POST | `/api/v1/organizations/{orgId}/tournaments/{tId}/league-transfers/relegate` | **上位 org ADMIN** | 降格送り出し。降格枠チームを出身県協会へ DISPATCHED 起票。body: `{ teamIds[], message? }` |
| GET | `/api/v1/organizations/{orgId}/inbound-transfers` | 受け入れ側 org ADMIN | 受信箱：自 org が `to_organization_id` の DISPATCHED 一覧（昇格受入＝親 org 視点 / 降格受入＝子 org 視点）。`direction` で絞込可 |
| POST | `/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/league-transfers/{id}/approve` | 受け入れ側 org ADMIN | 受け入れ承認＝配属。`target_division_id` セット・`status=PLACED`・`tournament_participant` 作成 |
| POST | `/api/v1/organizations/{orgId}/league-transfers/{id}/decline` | 受け入れ側 org ADMIN | 受け入れ拒否 → `status=DECLINED` |
| POST | `/api/v1/organizations/{orgId}/league-transfers/{id}/cancel` | 手放す側 org ADMIN | 送り出し取消（応答前のみ） → `status=CANCELLED` |
| GET | `/api/v1/teams/{teamId}/league-transfers` | 当該チーム MEMBER 以上 | チーム側：自チームの送り出し/受入状況の閲覧（読み取り専用） |

- **承認 EP の divId 帰属検証**: `divId → tId → orgId`（受け入れ側 org）の帰属を Service 層で IDOR チェーン検証する。
- `approve` で作成する `tournament_participant` は `target_division_id` のものに限定（指定外ディビジョンへの混入防止）。
- チーム側 API（`GET /teams/{teamId}/league-transfers`）は**閲覧のみ**。承認・拒否はあくまで org（主催者）が行う＝対称モデルの徹底。

---

## 7. 認可・セキュリティ

| 操作 | 許可ロール |
|------|-----------|
| 昇格送り出し（`promote`） | **下位（手放す側）リーグ大会の主催組織 ADMIN** / SYSTEM_ADMIN のみ |
| 降格送り出し（`relegate`） | **上位（手放す側）リーグ大会の主催組織 ADMIN** / SYSTEM_ADMIN のみ |
| 承認・配属（`approve`） | **受け入れ側組織 ADMIN**（昇格=上位 org / 降格=下位 org）のみ |
| 受け入れ拒否（`decline`） | 受け入れ側組織 ADMIN のみ |
| 送り出し取消（`cancel`） | 手放す側組織 ADMIN のみ（応答前 = `status=DISPATCHED` のときに限る） |
| チーム側閲覧（`GET /teams/{teamId}/league-transfers`） | 当該チーム MEMBER 以上 |

- **親子関係の正当性を `OrganizationHierarchyService` で必須検証**:
  - 昇格送り出し → 受け入れ先は送り出し元の `parent_organization_id` 系列（祖先 org）に限定（§5.3）。
  - 降格送り出し → 受け入れ先は送り出し元の子孫 ASSOCIATION に限定（§5.2）。
  - これにより**無関係 org へチームを送れない**（権限昇格・データ汚染の防止）。
- 送り出し（DISPATCHED 起票）は **手放す側 org の主催 ADMIN** のみ（昇格=下位 org、降格=上位 org）。受け入れ側の ADMIN は送り出しできない＝対称的に役割を分離。
- すべて team_id・division_id・organization_id を **ID 参照**（クロスドメイン FK なし・原則 1）。
- **存在しない対象は 404**（IDOR 統一）。他 org の移籍を操作しようとしても帰属検証で 404/403。
- **二重起票は `UNIQUE(team_id, season, direction)` で抑止**。
- 移籍系 API の認可方針は [docs/security/03_role_authority_model.md §15.3](../../security/03_role_authority_model.md) に集約記載する。
- **退会（O-4）**: `league_transfer.initiated_by`（送り出し起票者）・`responded_by`（承認/拒否応答者）の user_id は**履歴・証跡として保持**＝CLAUDE.md 退会二段モデルの**強匿名化対象外**（NULL 化しない。移籍の事実関係は組織運営の記録として保持価値が高い）。表示名のみ既存の匿名化に追従させ、退会後は匿名表示名で描画する。

---

## 8. 集計クエリの org 非依存検証（要件⑦の前提）

**検証必須**: `tournament-stats` / `tournament-history` の集計クエリが **organization で絞っていないこと**を実装時に確認する。

- もし organization で絞っていると、別組織のリーグへ昇格/降格した時点で通算成績・順位履歴が**切れて**しまう（要件⑦違反）。
- 絞っている場合は **org 絞りを撤廃**し、team_id 串刺しの集計に修正する（症状を隠さず根治）。
- 撤廃が他機能（組織別レポート等）に影響する場合は、別途「組織別フィルタは任意パラメータ」として切り出し、デフォルトは team_id 全体集計とする。

---

## 9. `PromotionService`（既存・同一大会内）との線引き

- `PromotionService.getPromotionPreview / executePromotions` と `tournament_promotion_records` は**同一大会内の部間移動専用**として維持する（再実装しない）。`getPromotionPreview` は隣接ディビジョン間のみを対象とし、**最上位部の昇格枠・最下位部の降格枠（＝組織またぎの対象）は返さない**（O-2・§3.3 で確認）。
- 本書 `league_transfer` は**組織をまたぐ移籍専用**。`PromotionService` に組織またぎロジックを混入させない。
- **同一大会内の隣接ディビジョン昇降格は `getPromotionPreview` を流用**するが、**組織またぎの境界枠（最上位/最下位）は `league_transfer` 側が `promotion_slots`/`relegation_slots` ＋ `tournament_standings` から独自判定**する（§3.3）。両者はカバー範囲が異なるため二重実装ではない。「getPromotionPreview を境界枠導出に再利用する／二重実装しない」という旧記述は O-2 として訂正済み。

---

## 10. 精査ログ

### 10.1 1 回目
- **不備**: 昇格・降格とも「プッシュ＋承認」の対称モデル（§1.1）で統一。`league_transfer` の状態機械（DISPATCHED→PLACED/DECLINED/CANCELLED）を両方向共通に定義。配属の二段（DISPATCHED→PLACED）で次シーズン未作成を吸収（§5.1）。
- **セキュリティ**: 送り出しは手放す側 org ADMIN・承認は受け入れ側 org ADMIN に役割分離（§7）。送り先を親系列（昇格）/子孫 ASSOCIATION（降格）に限定して無関係 org への送り出しを防止。チームは閲覧のみ（横取り・なりすまし不可）。二重起票 UNIQUE、404 統一、クロスドメイン FK なし。
- **ユーザビリティ**: 移籍が「簡単」（team_id 串刺しでデータ自動追従・コピー不要）。受信箱（inbound-transfers）で承認待ちを一覧。昇格・降格が同一 UI パターン（送り出し→受信箱→承認）。
- **見落とし**: 通算集計の org 非依存検証（§8）、送り先 0 件時の警告（§5.2/§5.3・症状を隠さない）、PromotionService 流用（§9）。
- **保守性**: 同一大会内 vs 組織またぎの責務分離（§2.1/§9）、UUIDv7（原則 6）、`AbstractTenantAwareRepository` 非適用の理由明記（§3.1）、状態機械の両方向共通化で実装重複を回避。

### 10.2 2 回目（検分1周目の指摘反映＝O-1/O-2/O-4/Y-4 根治）
- **O-1（実コード確認）**: `OrganizationHierarchyService` の公開メソッドは `getAncestors`/`getChildren` のみ。`hasAncestor`（:291）・`isDescendantMember`（:261）は private で後者はユーザー所属判定。org→org 真偽判定 API は不在 → §2 に**新規公開メソッド `isAncestorOf`/`isDescendantOf` の新設が必要**と明記し「既存でそのまま解決可能」を訂正。
- **O-2（実コード確認）**: `PromotionService.getPromotionPreview`（:46-99）は隣接ディビジョン間のみ（`i>0`/`i<size-1`）で、**最上位部の昇格枠・最下位部の降格枠（＝組織またぎ対象）を返さない**。§3.3/§4-1/§5.1-1/§6/§9 を「同一大会内＝getPromotionPreview 流用・組織またぎ境界枠＝league_transfer 側が `promotion_slots`/`relegation_slots`＋`tournament_standings` から独自判定」へ訂正。「二重実装しない」旧記述を是正。
- **O-4**: `initiated_by`/`responded_by` は証跡保持＝強匿名化対象外（§7）。
- **Y-4（実コード確認）**: `ParticipantStatus`＝`REGISTERED`/`ACTIVE`/`WITHDRAWN`/`DISQUALIFIED`（:6-10）。`PLACED` は league_transfer の状態であり participant は REGISTERED で作成する旨を §3.2 に注記。

### 10.3 未解決事項

**現時点でなし。**

### 10.4 変更履歴

| 日付 | 変更 |
|---|---|
| 2026-06-01 | **実装完了（BE）**: `league_transfer` テーブル（Flyway `V9.20260601150000`・UUIDv7・UNIQUE(team_id,season,direction)・from/to/team index）／`LeagueTransferEntity`／`LeagueTransferRepository`／`LeagueTransferService`（状態機械・境界枠独自判定・送り先解決＝祖先/子孫 ASSOCIATION 限定・0件/複数不明は `LEAGUE_TRANSFER_TARGET_NOT_RESOLVABLE` で例外化）／`LeagueTransferController`＋`TeamLeagueTransferController`（§6 全 EP）／`TournamentErrorCode` に TOUR_038〜045 追加。隊0 の `OrganizationHierarchyService.isAncestorOf/isDescendantOf` を利用。承認時 `tournament_participant` は `status=REGISTERED` で作成（Y-4）。test-first `LeagueTransferServiceTest` 28 件（状態遷移・境界枠・送り先解決・認可役割分離・他org操作404・チーム横取り不可・二重起票）green。**残**: 通知（F04.3/タイムライン）連結は別 Phase（イベントリスナー）／§8 集計クエリ org 非依存検証は別途。 |
| 2026-05-31 | **検分1周目の指摘を根治反映**: O-1（`OrganizationHierarchyService` に org→org 真偽判定の新規公開メソッド `isAncestorOf`/`isDescendantOf` が必要と明記・「既存で解決可能」を訂正）／O-2（`getPromotionPreview` は境界部の枠を返さないため、組織またぎの昇降格枠は `league_transfer` 側で `promotion_slots`/`relegation_slots`＋standings から独自判定と訂正）／O-4（`initiated_by`/`responded_by` は証跡保持＝強匿名化対象外）／Y-4（`league_transfer.status=PLACED` は transfer の状態・participant は REGISTERED で作成）。 |
| 2026-05-31 | **全面改訂**: 旧「昇格＝プル型招待 / 降格＝プッシュ送り出し」の非対称モデルを破棄し、**昇格・降格とも「プッシュ＋承認」の対称モデル**（手放す側 org が DISPATCHED 起票・受け入れ側 org が承認して PLACED）に統一。状態機械を両方向共通（DISPATCHED→PLACED/DECLINED/CANCELLED）に。API を `promote`/`relegate`（送り出し）・`inbound-transfers`（受信箱）・`approve`/`decline`/`cancel`（応答）に再設計。認可を「送り出し=手放す側 org ADMIN・承認=受け入れ側 org ADMIN」＋親子検証に置換。 |
| 2026-05-31 | 初版作成（リーグ・ピラミッド＋昇降格移籍）。 |
