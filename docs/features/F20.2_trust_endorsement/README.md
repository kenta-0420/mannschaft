# F20.2 団体認証「信頼の輪」— 信任（Trust Endorsement）

> **ステータス**: 🟡 設計中（精査待ち）
> **最終更新**: 2026-07-08
> **対象ドメイン（バックエンド）**: `com.mannschaft.app.trust`（新設）
> **対象パッケージ（フロントエンド）**: `frontend/app/pages/trust/**`（新設）/ `frontend/app/components/trust/**`（新設）
> **関連**:
> - [F00 ContentVisibilityResolver](../F00_content_visibility_resolver.md)（公開表示は PUBLIC 分岐を経由・独自可視性述語を作らない）
> - [F19.1 公開チーム・組織ページ](../F19.1_public_pages_identity_disclosure.md)（公開プロフィール／公開LP に認証マークを表示する導線）
> - F01.2 組織・チーム・メンバー・ロール管理（`teams` / `organizations` / `memberships`・`established_date`）
> - F09.19 広告実用化（`spotlight` 掲載枠に認証マークを表示）
> - マッチングドメイン（認証済みマークの表示先）
> - F20.1 権利・課金（非営利優遇の条件フックとして「信任を受けた認証済み団体」を参照可・**本設計は F20.1 に依存しない／並行作成中のためパス参照のみ**）
> - `docs/security/03_role_authority_model.md`（信任付与の認可・IDOR 対策の根拠）

---

## 0. この設計書の構成

複合形（F08.9 / F22.1 payment と同じ分割方式）で構成する。

| ファイル | 内容 |
|---|---|
| `README.md`（本書） | 概要・中核モデル（一方向 DAG の信任グラフ／状態機械／アンカー／資格条件）・受け入れ条件 AC・スコープ・状態遷移図・段階ロードマップ・要裁可論点・変更履歴 |
| [`01_data_model.md`](01_data_model.md) | DB設計（`trust_certifications` / `trust_endorsements` の完全な CREATE TABLE・enum 全値・年間上限の持ち方・ER図・Flyway 仮採番・DB 原則適合チェック） |
| [`02_api_design.md`](02_api_design.md) | API設計（信任の付与/取消・認証状態取得・信任関係一覧（公開）・資格判定・運営用アンカー付与/REVOKE/再審査キュー・DTO 全フィールド・エラーコード・状態遷移の擬似コード・冪等性） |
| [`03_security.md`](03_security.md) | セキュリティ（認可マトリクス・信任付与の scopeId 所有権検証（IDOR）・運営 API の SYSTEM_ADMIN 認可・可視性 F00 連結・レート制限・GDPR/退会・監査） |
| [`04_ui_i18n.md`](04_ui_i18n.md) | 画面設計（認証マーク・信任関係の公開表示・団体管理者の信任付与/取消 UI・運営レビューキュー UI）・i18n 6言語キー（`trust.json` 新設） |

---

## 1. 概要

### 1.1 目的

団体（チーム／組織）の**実在性・信頼性を、運営の手審査ではなくコミュニティの相互推薦（信任）で認証する**仕組みである。既に認証済みの団体から一定数の**信任**を受けた団体を「認証済み（`CERTIFIED`）」とし、認証マークを公開表示する。運営が全団体を審査するスケール限界を、信頼グラフの自律的拡大で突破することを狙う。

### 1.2 呼称（厳守）

| 区分 | 用語 | 英語 |
|---|---|---|
| 認証の行為・関係 | **信任** | trust endorsement |
| 機能全体の愛称 | **信頼の輪** | — |
| 認証済みの状態 | **認証済み** / `CERTIFIED` | certified |

> **NG語（設計書・API・DB・i18n 文言すべてで禁止）**:
> - **「保証」**（法的責任を連想させる）
> - **「後見」**（保護者連携ドメイン F08.9 系・F01.9 と衝突）
> - **「承認」**（汎用の approve と混同）
> - **「相互認証」**（本機能の構造は**一方向**であり mTLS 用語とも衝突）
>
> 認証の関係は常に「A が B を**信任する**（endorse）」の一方向で記述する。「相互」「お互い」という語を使わない。

### 1.3 中核原則（一目で）

1. **一方向の信頼グラフ（アンカー起点の DAG）**: 信任を発行できるのは**認証済み団体のみ**。信頼グラフは運営が付与した**アンカー**から外側へ伸びる。未認証団体だけで信任リングを組んで自己認証することを**構造的に阻止**する。
2. **閾値 3 で認証**: 有効な信任を**3 件**受けた時点で `CERTIFIED` に遷移する（閾値 3 は確定値・ただし定数化し運用変更可能に）。
3. **自動連鎖剥奪はしない**: 信任元の認証取消等で有効信任が 3 未満になっても、被信任団体は**即座に剥奪されず** `UNDER_REVIEW`（再審査フラグ）に留まり、認証マーク表示は維持しつつ運営キューへ回る。連鎖は**1 段まで**。
4. **課金と完全分離・無料**: 認証マークは**お金で買えない**。信任・認証はすべて無料。課金ドメイン（F08.9 / F22.1 / F20.1）と結線しない。
5. **可視性は F00 経由**: 公開表示は `AbstractContentVisibilityResolver` の PUBLIC 分岐を通す。独自の可視性述語を作らない。

### 1.4 スコープ用語

本機能の対象主体（信任元・信任先）は **TEAM / ORG** のみ。**USER は対象外**（個人は信任の主体にも対象にもならない）。スコープ種別は payment ドメインの `ScopeKind{USER, TEAM, ORG}`（`com.mannschaft.app.payment.connect.ScopeKind`）に準拠して `scope_kind ENUM('TEAM','ORG')` とする（USER は本機能では CHECK 制約で拒否）。

> ⚠️ **実装注意 — `scope_kind` 値と membership 系 `scope_type` のマッピング**
> 本機能の `scope_kind` は payment 準拠で `TEAM` / `ORG` の 2 値だが、`memberships.scope_type`（`com.mannschaft.app.membership.domain.ScopeType`）は **`TEAM` / `ORGANIZATION`** の 2 値である（`ORG` ではなく `ORGANIZATION`）。アクティブメンバー数のカウント（資格判定 §3.3）で `memberships` を引く際は必ず以下でマッピングすること:
> - `scope_kind = 'TEAM'` → `scope_type = 'TEAM'`（1:1）
> - `scope_kind = 'ORG'` → `scope_type = 'ORGANIZATION'`（**文字列不一致に注意**）
> 変換ロジックは `TrustScopeResolver` 等 1 箇所に集約し、文字列直比較を散在させない。

---

## 2. スコープ

### 2.1 対象（in）

- [ ] 信任の付与（認証済み団体 → 対象団体・endorser 側管理者操作）
- [ ] 信任の取消（endorser 側管理者操作）
- [ ] 認証状態（`UNCERTIFIED` / `CERTIFIED` / `UNDER_REVIEW` / `REVOKED`）の管理と自動遷移（有効信任 3 件で `CERTIFIED`）
- [ ] アンカー（運営手動付与の初期認証・`is_anchor`）
- [ ] 信任資格の最低条件判定（認証済み＋設立 N ヶ月経過＋アクティブメンバー M 人以上＋年間信任発行上限）
- [ ] 認証状態取得 API・信任関係一覧 API（公開・双方向 incoming/outgoing）
- [ ] 認証マークの公開表示（F00 PUBLIC 分岐経由・マッチング／F09.19 広告／F19.1 公開LP・公開チームページ）
- [ ] 信任関係の双方公開（誰が誰を信任しているか）
- [ ] 運営 API（アンカー付与・REVOKE・再審査キュー・再審査からの認証復帰）
- [ ] 信任付与／`UNDER_REVIEW` 遷移時の通知（既存 notification ドメイン）
- [ ] **団体削除時の信任カスケード**（削除団体の outgoing 有効信任を無効化＋被信任先の有効数再計算・1 段。`TeamDeletedEvent`/`OrganizationDeletedEvent` 購読・[02 §5.4](02_api_design.md)・AC-27）
- [ ] 監査ログ（信任付与・取消・REVOKE・アンカー付与・状態遷移）

### 2.2 対象外（out）

- [ ] USER（個人）の信任（主体・対象いずれも不可）
- [ ] 課金・有料バッジ（**設計として不採用**・無料原則）
- [ ] F20.1 非営利優遇の**判定実装**（本設計はフック（参照点）のみ記述し F20.1 に依存しない）
- [ ] 信任の重み付け・スコアリング（1 信任 = 1 票・重み無し）
- [ ] 認証マークの外部埋め込みウィジェット（v2 以降）
- [ ] 信任元の匿名化（信任関係は公開が前提）
- [ ] **信任の依頼フロー**（未認証団体が認証済み団体へ「信任してほしい」と依頼する導線）— 本機能スコープ外。信任は endorser 側の自発的操作のみとし、依頼・招待の導線は将来拡張点として記録する
- [ ] 厳密な非巡回（acyclicity）検証（**要裁可論点 §11-1**・既定は「信任元は認証済み」不変条件のみで未認証リングを阻止）

---

## 3. 中核モデル

### 3.1 信任グラフ（一方向・アンカー起点）

```
  [運営] ──アンカー付与──▶ (A: CERTIFIED/anchor)
                                 │ 信任
                                 ▼
  (A)─┐                     (X: UNCERTIFIED)
  (B)─┼──3件の有効信任──▶  (X→ CERTIFIED)   ← A,B,C はいずれも CERTIFIED（未認証は信任できない）
  (C)─┘
```

- **信任を発行できるのは `CERTIFIED`（アンカー含む）団体のみ**（`TRUST_001` 信任元未認証で拒否）。これにより「未認証団体だけの信任リング」で自己認証する経路を**構造的に**塞ぐ。信頼グラフは必ず運営付与のアンカーを根に持つ。
- **自己信任は不可**（`endorser == endorsee` は `TRUST_002` で拒否・DB CHECK でも拒否）。
- **重複信任は不可**（同一 endorser→endorsee の有効な信任は 1 件のみ・`TRUST_005`・DB UNIQUE でも拒否）。
- 信任は**方向を持つ**（A が B を信任する ≠ B が A を信任する）。「相互認証」ではない。A→B と B→A が両方立ちうるかは §11-1（要裁可）で扱う。

### 3.2 状態機械

| 状態 | 意味 | 認証マーク表示 |
|---|---|---|
| `UNCERTIFIED` | 未認証（初期状態・信任を受けていないか有効信任 3 未満で一度も認証到達していない） | 非表示 |
| `CERTIFIED` | 認証済み（有効信任 3 件到達・またはアンカー） | **表示** |
| `UNDER_REVIEW` | 再審査中（一度 `CERTIFIED` に到達後、有効信任が 3 未満に低下・運営レビュー待ち） | **表示を維持** |
| `REVOKED` | 運営により認証取消（不正・虚偽等） | 非表示 |

**遷移トリガと副作用**は §5 の状態遷移表に集約する。要点:

- `UNCERTIFIED →(3 件目の有効信任)→ CERTIFIED`（自動）。
- `CERTIFIED →(有効信任 3 未満へ低下)→ UNDER_REVIEW`（自動・**マーク維持**・運営キュー投入・自動剥奪しない）。
- `UNDER_REVIEW →(有効信任 3 件以上へ回復)→ CERTIFIED`（自動）／`UNDER_REVIEW →(運営の再審査 OK)→ CERTIFIED`（手動）。
- `* →(運営操作)→ REVOKED`（手動）。
- **アンカー団体は `UNDER_REVIEW` へ落ちない**（`is_anchor=TRUE` は信任数に依存せず `CERTIFIED` を維持・§5.2）。

### 3.3 信任資格の最低条件（すべて定数・運用値）

信任を**発行する側**（endorser）が満たすべき条件。いずれか欠けると `TRUST_003`（資格未達）で拒否する。設定は `mannschaft.trust.*`（config properties）で外部化し、運用で変更可能とする。

| 条件 | 既定値（定数） | 判定 | 根拠 |
|---|---|---|---|
| **認証済みであること** | — | `trust_certifications.state = 'CERTIFIED'`（`is_anchor` 含む） | 未認証は信任できない（一方向グラフ・§3.1） |
| **設立から N ヶ月経過** | `min-established-months = 6` | `established_date`（V3.132 org / V3.133 team）＋精度考慮（§3.4）で判定 | 即席団体による信任乱発を防ぐ |
| **アクティブメンバー M 人以上** | `min-active-members = 5` | 既存 `MembershipRepository.countActiveDistinctUsersByScope(ScopeType, scopeId)` を再利用（**新規 count メソッドを作らない**）。「アクティブ」＝**在籍（`left_at IS NULL`）ベースの DISTINCT ユーザー数**（同一 user の複数行を二重計上しない・role_kind 横断）。`users.status` との連動は行わない（同メソッドの JavaDoc どおり user ドメインに委ね、membership から users を直接参照しない）。scope_type は §1.4 マッピング | 実体のある団体に限定 |
| **年間信任発行数の上限** | `annual-endorsement-cap = 10` | 直近 12 ヶ月に当該 endorser が発行した有効信任の件数（§3.5） | 単一団体による信任の希釈・買収的発行を防ぐ |

> 閾値は「安全側の既定」を置き、運用で緩める前提。値は本設計の推奨であり、**マスター確定値ではない**（§11-2 要裁可）。

### 3.4 `established_date` の精度（`established_date_precision`）の扱い

`established_date`（DATE）は日不明時 `01` 埋め・`established_date_precision ENUM('YEAR','YEAR_MONTH','FULL')`（V3.132/V3.133・NULL あり）で精度を持つ。設立から N ヶ月経過の判定は**保守側**（実際の設立日が最も遅い可能性を採る）で行う。

| precision | 実効設立日（`effectiveEstablished`） | 例（`established_date=2025-01-01`） |
|---|---|---|
| `FULL` | `established_date` そのまま | 2025-01-01 |
| `YEAR_MONTH` | その月の**末日**（`established_date` を月末に丸め） | 2025-01-31 |
| `YEAR` | その年の**12/31** | 2025-12-31 |
| `NULL`（精度不明） | **設立日を検証不能として扱い、資格を満たさない**（`TRUST_003`） | — |
| `established_date IS NULL` | 同上（`TRUST_003`・設立日の登録を促す） | — |

判定式: `effectiveEstablished.plusMonths(minEstablishedMonths) <= today` を満たすとき「設立 N ヶ月経過」とする。**保守側に丸める**ことで、精度の粗い団体が実際より若いのに資格を得る誤認を防ぐ（症状を隠さない・CLAUDE.md 根治原則）。擬似コードは [02 §4.1](02_api_design.md)。

### 3.5 年間信任発行数の持ち方（設計判断・推奨）

年間上限（`annual-endorsement-cap`）の管理方法は 2 案あり、**案 B（`trust_endorsements` の日時集計）を推奨**する。

| 案 | 方法 | 長所 | 短所 |
|---|---|---|---|
| **案 A** | `trust_certifications` にカウンタ列（`annual_endorsement_count` ＋ `annual_window_started_at`） | 参照が O(1) | **窓リセットのバッチが必要**・二重管理でドリフトしうる・取消時のデクリメント整合が複雑（症状を隠す温床） |
| **案 B（推奨）** | 列を持たず、`trust_endorsements` を `endorser_scope_kind/id` ＋ `granted_at >= now-12ヶ月` ＋ `revoked_at IS NULL` で **COUNT** | 単一の真実源（信任テーブル）・取消は `revoked_at` セットで自動的に件数から外れる・ドリフトなし | 発行時に 1 回 COUNT クエリ（インデックスで軽量・[01 §3.2](01_data_model.md) の `idx_te_endorser_granted` で被覆） |

**推奨＝案 B**。理由: カウンタ列（案 A）は「窓のリセット」「取消時のデクリメント」で二重管理となり、CLAUDE.md の「症状を隠さない／単一の真実源」原則に反する。信任テーブルの `granted_at`／`revoked_at` を集計すれば、取消・失効が即座に件数へ反映され整合が自動保証される。ローリング 12 ヶ月の COUNT は `(endorser_scope_kind, endorser_scope_id, granted_at)` の複合インデックスで高々数十行スキャンに収まり、1000 万ユーザー規模でも問題にならない（§9）。

> **年間上限の集計対象は「有効な発行」**（`revoked_at IS NULL`）に限る。信任先団体が削除されても、その信任は endorser の outgoing としては無効化されない（削除カスケードは endorser 側削除時のみ・§3.7/§5.1 T12）。したがって**削除された団体宛ての信任は 12 ヶ月の窓が過ぎるまで endorser の年間発行数に残り続ける**（不変台帳＝`granted_at` 基準集計の帰結）。これは**許容**する（endorser が実際に信任枠を消費した事実は変わらないため。窓経過で自然に外れる）。endorser が枠を取り戻したければ自身が当該信任を取り消せば `revoked_at` セットで即座に件数から外れる。

### 3.6 アンカー（初期認証のブートストラップ）

- 信頼グラフには根が必要なため、運営が**手動でアンカーを付与**する（`is_anchor=TRUE` かつ `state=CERTIFIED`）。対象は**活動実績のあるベータ参加団体**を想定。
- アンカーは信任数に依存せず `CERTIFIED` を維持し、`UNDER_REVIEW` へ落ちない（§5.2）。
- アンカー付与・解除は運営 API（SYSTEM_ADMIN）でのみ可能（[02 §6](02_api_design.md)）。
- アンカーは「信任を発行できる最初の認証済み団体」として機能し、そこから信任が外へ伸びて他団体が `CERTIFIED` になっていく。

### 3.7 REVOKED の連鎖（1 段まで・厳密定義）

団体 X が運営操作で `REVOKED` になったとき:

1. X が発行した**すべての有効信任**（`endorser = X` かつ `revoked_at IS NULL`）を無効化する（`revoked_at = now`・`revoke_reason = 'ENDORSER_REVOKED'`）。
2. X から信任を受けていた各被信任先 Y について、**有効信任の件数を再計算**する。
3. Y の有効信任が 3 未満に落ち、かつ Y が `CERTIFIED` かつ非アンカーなら → **Y を `UNDER_REVIEW` に遷移**（マーク維持・運営キュー投入）。
4. **連鎖は Y までの 1 段で止める**。Y が `UNDER_REVIEW` になっても、Y が発行した信任は**無効化しない**（Y はまだ認証マーク表示中＝信任発行資格の `CERTIFIED` 判定上は「認証済み扱い」を維持するかは §11-3 要裁可）。Y の被信任先へは波及させない。

> **1 段制限の狙い**: REVOKED の連鎖剥奪が信頼グラフ全体を巻き込んで崩壊させる（カスケード障害）ことを防ぐ。運営が Y を個別にレビューして判断する猶予を作る。擬似コードは [02 §5.3](02_api_design.md)。

---

## 4. 受け入れ条件（AC）

「誰が・何をしたら・どうなる（観測可能）」で記述する。正常＋異常＋境界を網羅する。詳細な API/DDL 対応は 01〜04 に対応付ける。

### 4.1 信任の付与・認証遷移

- **AC-01**（正常・認証遷移）: 認証済み団体 A・B・C がそれぞれ未認証団体 X を信任し、**3 件目（C）の有効信任が付与された時点**で X の `state` が `UNCERTIFIED` から `CERTIFIED` に遷移し、`certified_at` が記録される。
- **AC-02**（境界・2 件では遷移しない）: A・B の 2 件のみ信任した時点では X は `UNCERTIFIED` のまま（`certified_at` は NULL）。
- **AC-03**（境界・ちょうど 3 件で遷移）: 3 件目の付与トランザクション内で `CERTIFIED` に遷移する（4 件目以降の付与では状態は変わらず `CERTIFIED` を維持）。
- **AC-04**（異常・信任元未認証）: `state = CERTIFIED`（アンカー含む）**でない**団体（`UNCERTIFIED`・`UNDER_REVIEW`・`REVOKED` のいずれか）が信任 API を呼ぶと `TRUST_001`（信任元未認証・422）で拒否され、`trust_endorsements` に行が作られない。
- **AC-05**（異常・自己信任）: 団体 A が自団体 A を信任しようとすると `TRUST_002`（自己信任・422）で拒否される。
- **AC-06**（異常・重複信任）: 既に A→X の有効信任がある状態で再度 A→X を付与しようとすると `TRUST_005`（重複信任・409）で拒否される（DB UNIQUE でも二重防御）。
- **AC-07**（異常・年間上限超過）: A が直近 12 ヶ月に上限（既定 10）件の有効信任を発行済みの状態で 11 件目を付与しようとすると `TRUST_004`（年間上限超過・429）で拒否される。
- **AC-08**（境界・上限ちょうど）: **Clock 固定下で**、A の直近 12 ヶ月の有効信任が 9 件のとき 10 件目は成功し、その直後 11 件目は `TRUST_004`。1 件取消して 9 件に戻せば再び 1 件付与できる（年間上限系は Clock 注入で時刻を制御できる統合テストで担保・[02 §10](02_api_design.md)）。
- **AC-09**（異常・資格未達・設立）: 設立 N ヶ月未満（または `established_date`/`precision` が NULL）の認証済み団体が信任しようとすると `TRUST_003`（資格未達・422）で拒否される。
- **AC-10**（異常・資格未達・人数）: アクティブメンバーが M 人未満の認証済み団体が信任しようとすると `TRUST_003` で拒否される。
- **AC-11**（異常・対象スコープ不正）: `scope_kind=USER` を信任元または信任先に指定すると `TRUST_006`（対象スコープ不正・422）で拒否される。
- **AC-12**（認可・endorser 管理者以外）: endorser 団体の管理者権限を持たないユーザーが当該団体名義で信任 API を呼ぶと `TRUST_009`（認可エラー・403／無関係 scope は 404 秘匿）。
- **AC-13**（認可・scopeId 所有権/IDOR）: あるユーザーが自分が管理しない別団体の `scopeId` を endorser に詐称して信任を発行しようとしても、scopeId 所有権検証で `TRUST_009`（403／404 秘匿）となり成立しない（`getCurrentUserId()` を scopeId に流用しない・[03 §3](03_security.md)）。

### 4.2 信任の取消・降格・連鎖

- **AC-14**（正常・取消で降格）: X が A・B・C の 3 件で `CERTIFIED` の状態で、A が A→X の信任を取り消すと有効信任が 2 件になり、X は `UNDER_REVIEW` に遷移するが**認証マーク表示は維持**され、運営レビューキューに載る。
- **AC-15**（連鎖・REVOKE で 1 段降格）: 信任元 A が運営により `REVOKED` になると、A が発行した信任がすべて無効化され、A の信任で `CERTIFIED` だった X の有効信任が 3 未満になった場合 X は `UNDER_REVIEW` になる（認証マーク維持）。
- **AC-16**（連鎖 1 段制限）: AC-15 で X が `UNDER_REVIEW` に落ちても、X が発行していた信任は無効化されず、X の被信任先はこの REVOKE の影響で状態変化しない（連鎖は 1 段で止まる）。
- **AC-17**（正常・回復）: `UNDER_REVIEW` の X が新たに認証済み団体 D から信任を受け有効信任が 3 件に回復すると、X は自動で `CERTIFIED` に戻る（`certified_at` は最初の到達時刻を保持）。
- **AC-18**（正常・取消で完全未認証化はしない）: 一度も `CERTIFIED` に到達していない X の有効信任が 3→2 になっても X は `UNDER_REVIEW` にならず `UNCERTIFIED`（そもそも `CERTIFIED` 未到達なので降格対象外）。
- **AC-19**（境界・アンカーは降格しない）: `is_anchor=TRUE` の団体は受けている有効信任が 0 件でも `CERTIFIED` を維持し `UNDER_REVIEW` に落ちない。
- **AC-27**（団体削除カスケード・T12）: 認証済み団体 A が削除（`TeamDeletedEvent`/`OrganizationDeletedEvent`）されると、A が発行していた有効信任がすべて無効化され（DB: 各行の `revoked_at` NOT NULL・`revoke_reason='ENDORSER_DELETED'`）、A から信任を受けていた X の有効信任が 3 未満になった場合 X の `state` が `UNDER_REVIEW` に再計算される（連鎖 1 段・AC-16 と同じ停止規則）。
- **AC-28**（境界・T5）: `UNDER_REVIEW` の X（有効 1 件）が新たに 1 件の信任を受けても計 2 件（3 未満）なら X は `UNDER_REVIEW` のまま（DB: 信任行は増える・`state` 不変・通知は受任通知のみ）。
- **AC-29**（境界・T7・4→3 では降格しない）: 有効信任 4 件で `CERTIFIED` の X から 1 件が取り消され 3 件になっても X は `CERTIFIED` のまま（`state` 不変）。さらに 1 件取り消され 2 件になった時点で初めて `UNDER_REVIEW` に遷移する（AC-14）。
- **AC-32**（異常・取消の冪等）: 既に取消済み（`revoked_at` NOT NULL）の信任に再度取消 API を呼ぶと `TRUST_008`（409）で拒否され、DB 状態は変化しない。

### 4.3 運営 API・公開表示

- **AC-20**（運営・アンカー付与）: SYSTEM_ADMIN がアンカー付与 API を呼ぶと対象団体の `is_anchor=TRUE`・`state=CERTIFIED`・`certified_at` が記録され、以後その団体は信任を発行できる。
- **AC-21**（運営・REVOKE）: SYSTEM_ADMIN が REVOKE API を呼ぶと対象の `state=REVOKED`・認証マーク非表示になり、§3.7 の連鎖処理が実行される。非 SYSTEM_ADMIN は 403。
- **AC-22**（運営・再審査キュー）: SYSTEM_ADMIN が再審査キュー API を呼ぶと `state=UNDER_REVIEW` の団体一覧が返る。再審査 OK で `CERTIFIED` に復帰、NG で `REVOKED` にできる。
- **AC-23**（公開表示・認証状態取得）: 未ログインユーザーが認証状態取得 API を呼ぶと、対象団体が PUBLIC のとき**公開用に丸めた state**（`UNDER_REVIEW` は `CERTIFIED` として返す・[02 §6.1](02_api_design.md)）と `badgeVisible` が返り、PRIVATE 団体は F00 経由で 404 秘匿になる。生の `UNDER_REVIEW` は当該団体管理者・運営向け DTO でのみ観測できる。
- **AC-24**（公開表示・信任関係一覧）: 未ログインユーザーが信任関係一覧 API を呼ぶと、対象団体が誰を信任し（outgoing）・誰から信任されているか（incoming）の双方が公開される（信任関係は双方のプロフィールに公開）。**ただし公開面に載るのは相手方が viewer から F00 可視である信任に限る**（PRIVATE 団体を相手とする信任は公開一覧から除外・安全側既定 §11-7(b)）。**件数 `validEndorsementCount` は除外に関わらず全件をカウント**する（[02 §6.2](02_api_design.md)・[03 §4.1](03_security.md)）。
- **AC-25**（通知・付与）: X が 3 件目の信任で `CERTIFIED` になったとき、X の団体管理者に「認証済みになった」通知が届く（通常通知）。信任を受けた（1〜2 件目）時点でも X 管理者へ通常通知が届く。
- **AC-26**（通知・降格）: X が `UNDER_REVIEW` に遷移したとき、X の団体管理者に「信任状況に変化があった」通常通知が届く（確認必須通知は使わない・§8）。
- **AC-30**（運営・アンカー解除・A3）: SYSTEM_ADMIN がアンカー解除 API を呼ぶと対象の `is_anchor=FALSE` になり、通常団体として再評価される（有効信任 3 件以上なら `CERTIFIED` 維持・3 未満なら `UNDER_REVIEW`。API 応答の `state` と DB で観測）。非アンカー団体への解除は `TRUST_010`（409）。
- **AC-31**（運営・アンカーへの REVOKE・A4）: アンカー団体（`is_anchor=TRUE`）にも REVOKE は有効で、`state=REVOKED`・認証マーク非表示になり、outgoing 信任の全無効化＋1 段連鎖（§3.7）が実行される。
- **AC-33**（異常・運営操作の状態不整合）: `UNDER_REVIEW` でない団体への再審査 approve/reject、既に `REVOKED` の団体への再 REVOKE は `TRUST_010`（409）で拒否され、状態は変化しない。
- **AC-34**（資格事前確認）: endorser 団体の管理者が資格確認 API（[02 §4.2](02_api_design.md)）を呼ぶと、資格を満たす場合 `eligible=true` と年間発行残数が返り、未達の場合 `eligible=false` と未達理由（`blockingReasons`）の全列挙が返る（API 応答で観測）。非管理者は 403／無関係 scope は 404。

> 試練（テスト先行）では AC-01〜34 を BE ドメイン UT ＋ API 契約テストの red として起こす（`feedback_test_first_be_api`）。特に AC-02/03/08/28/29（境界）、AC-04〜11/32/33（異常）、AC-15/16/27（連鎖 1 段・削除カスケード）を落としてはならない。

---

## 5. 状態遷移表

イベント × 現状態 → 次状態・副作用。`n` = 対象団体の有効信任件数（`revoked_at IS NULL` の incoming 信任数）、閾値 `T=3`。

### 5.1 通常団体（`is_anchor=FALSE`）

| # | イベント | 現状態 | 条件 | 次状態 | 副作用 |
|---|---|---|---|---|---|
| T1 | 信任付与（incoming） | `UNCERTIFIED` | 付与後 `n < T` | `UNCERTIFIED` | 信任行 INSERT・被信任団体管理者へ通常通知（受任） |
| T2 | 信任付与（incoming） | `UNCERTIFIED` | 付与後 `n = T`（3 件目） | `CERTIFIED` | `certified_at=now`・認証マーク表示化・被信任団体管理者へ通常通知（認証達成）・監査ログ |
| T3 | 信任付与（incoming） | `CERTIFIED` | 付与後 `n > T` | `CERTIFIED` | 信任行 INSERT のみ（状態不変） |
| T4 | 信任付与（incoming） | `UNDER_REVIEW` | 付与後 `n ≥ T` | `CERTIFIED` | 再審査解除・キューから除去・監査ログ・管理者へ通常通知（認証回復） |
| T5 | 信任付与（incoming） | `UNDER_REVIEW` | 付与後 `n < T` | `UNDER_REVIEW` | 信任行 INSERT のみ |
| T6 | 信任取消／信任元 REVOKE で無効化（incoming 減） | `CERTIFIED` | 取消後 `n < T` | `UNDER_REVIEW` | 運営レビューキュー投入・**認証マーク維持**・管理者へ通常通知（状況変化）・監査ログ |
| T7 | 信任取消（incoming 減） | `CERTIFIED` | 取消後 `n ≥ T` | `CERTIFIED` | 信任行 `revoked_at` セットのみ（状態不変） |
| T8 | 信任取消（incoming 減） | `UNCERTIFIED` | — | `UNCERTIFIED` | 一度も `CERTIFIED` 未到達＝降格対象外（状態不変） |
| T9 | 運営 REVOKE | `CERTIFIED`/`UNDER_REVIEW`/`UNCERTIFIED` | — | `REVOKED` | 認証マーク非表示・当該団体の outgoing 信任を全無効化 → §3.7 連鎖（1 段）・監査ログ |
| T10 | 運営 再審査 OK | `UNDER_REVIEW` | — | `CERTIFIED` | 再審査解除・キューから除去・監査ログ（`n < T` でも運営裁量で復帰可） |
| T11 | 運営 再審査 NG | `UNDER_REVIEW` | — | `REVOKED` | T9 と同じ副作用 |
| T12 | **団体削除**（`TeamDeletedEvent`/`OrganizationDeletedEvent` 購読・[02 §5.4](02_api_design.md)） | any | — | （認証行は現状態のまま・団体自体が F00 不可視化） | 当該団体の **outgoing 有効信任を全無効化**（`revoke_reason='ENDORSER_DELETED'`）→ 被信任先の有効数再計算（`CERTIFIED` かつ非アンカーで `n < T` なら `UNDER_REVIEW`・連鎖 1 段）・監査ログ |

### 5.2 アンカー団体（`is_anchor=TRUE`）

| # | イベント | 現状態 | 次状態 | 副作用 |
|---|---|---|---|---|
| A1 | アンカー付与（運営） | any | `CERTIFIED`（`is_anchor=TRUE`） | `certified_at=now`・監査ログ |
| A2 | 信任取消／信任元 REVOKE で `n < T` | `CERTIFIED` | `CERTIFIED`（維持） | **降格しない**（アンカーは信任数非依存・T6 の例外） |
| A3 | 運営アンカー解除 | `CERTIFIED` | `n ≥ T ? CERTIFIED : UNDER_REVIEW` | `is_anchor=FALSE` に戻し、通常団体として再評価 |
| A4 | 運営 REVOKE | any | `REVOKED` | T9 と同じ（アンカーでも運営は REVOKE 可） |

---

## 6. F00 可視性基盤・公開表示

- 認証状態・信任関係の公開可否は、対象団体（TEAM/ORG）の `visibility`（PUBLIC/PRIVATE）に従い、**F00 `AbstractContentVisibilityResolver` の PUBLIC 分岐**を経由して判定する。独自述語を作らない（`feedback_visibility_bypass_f00_audit`）。
- 実装は F00 のファサード **`ContentVisibilityChecker.canView(ReferenceType.TEAM | ReferenceType.ORGANIZATION, scopeId, viewerUserIdOrNull)`** を呼ぶ（未ログインは `userId=null`）。実体は既存の `TeamVisibilityResolver`（`team.visibility`）/ `OrganizationVisibilityResolver`（`organization.visibility`）で、`ContentVisibilityChecker` が `ReferenceType` でディスパッチする。認証状態取得・信任関係一覧の公開クエリは「対象 scope が可視」を先に確認してから認証情報を返す（[03 §4](03_security.md)）。※F19.1 の `IdentityVisibilityResolver` は**投稿者識別（氏名・アバター）の段階開示用で別物**・本機能では使わない。
- 認証マークの表示先（読み取り専用の消費側）:

| 表示先 | 参照する状態 | 備考 |
|---|---|---|
| マッチングドメイン（相手団体カード） | `state ∈ {CERTIFIED, UNDER_REVIEW}` を「認証済み」表示 | `UNDER_REVIEW` も表示維持（§3.2） |
| F09.19 広告 `spotlight` 掲載枠 | 同上 | 掲載主体団体の認証マーク |
| F19.1 公開チーム／組織ページ・公開LP | 同上 | 未ログインにも表示 |
| 認証済み団体プロフィール | 信任関係（incoming/outgoing）一覧 | 双方公開 |

> 「認証済み表示」の判定は `CERTIFIED` と `UNDER_REVIEW` の 2 状態（`REVOKED`/`UNCERTIFIED` は非表示）。この判定ヘルパ `TrustBadgeVisibility.isBadgeVisible(state)` を 1 箇所に集約し、消費側で状態比較を散在させない。

---

## 7. F20.1 との接点（参照のみ・依存しない）

F20.1（権利・課金）で「非営利区分に大きな優遇を付ける」場合、その付与条件に「**信任を受けた認証済み団体であること**」を使えるよう、本機能は認証状態を照会できる内部サービス `TrustCertificationQueryService.isCertified(scopeKind, scopeId)` を公開する。

- F20.1 は本サービスを**参照するだけ**で、本機能は F20.1 の存在を前提としない（`trust` ドメインは `entitlement`/`billing` ドメインに依存しない・一方向）。
- F20.1_entitlement_billing は**並行作成中**のため、本設計はパス参照（`docs/features/F20.1_entitlement_billing/`）に留め、結線・DTO 共有はしない。
- 本フックは「認証を課金の割引条件に使う」ものであって「課金で認証を買う」ものではない（§1.3-4 無料原則を侵さない）。
- **F20.3（ベータ特典）との依存方向も同じ**: trust は F20.3 に依存しない。F20.3 側が `TrustCertificationQueryService.isCertified(...)` を参照する（依存は常に 外部ドメイン → trust の一方向）。

---

## 8. 通知（既存機構の指定）

信任付与／`UNDER_REVIEW` 遷移で被信任団体の管理者へ通知する。**確認必須通知（F04.9 `ConfirmableNotificationService`）は使わず、通常通知（`notification` ドメインの `NotificationEntity`・`actionUrl`）を使う**。

| イベント | 通知種別 | 宛先 | 文言（i18n キー） | 理由 |
|---|---|---|---|---|
| 信任受任（1〜2 件目） | 通常通知 | 被信任団体の ADMIN 群 | `trust.notify.endorsed` | 情報提供のみ・確認義務なし |
| 認証達成（3 件目・`CERTIFIED`） | 通常通知 | 同上 | `trust.notify.certified` | 情報提供・祝意 |
| `UNDER_REVIEW` 遷移 | 通常通知 | 同上 | `trust.notify.underReview` | **被信任側に確認義務動作がない**（マーク維持で運営が審査する）ため確認必須通知は過剰 |
| 認証回復（`CERTIFIED` 復帰） | 通常通知 | 同上 | `trust.notify.recertified` | 情報提供 |

> **F04.9 を使わない根拠**: `ConfirmableNotificationService` は「受信者が確認/期限内アクションを取る必要がある」通知（協会請求の支払い等）向け。信任・降格は被信任側にアクションを要求しない（運営がレビューする）ため、通常通知で足りる（過剰設計を避ける）。運営レビューキューは通知ではなく専用 API（[02 §6](02_api_design.md)）で扱う。

---

## 9. 非機能（1000 万ユーザー耐性）

- **信任グラフ探索は不要**: 認証判定は「対象団体の incoming 有効信任を高々 3 件確認できれば十分」（`n ≥ 3` の存在確認）。グラフの深い探索・推移閉包計算は行わない。認証状態は `trust_certifications.state` に**マテリアライズ**して保持し、読み取りは 1 行 SELECT。
- **年間上限の集計**は `idx_te_endorser_granted (endorser_scope_kind, endorser_scope_id, granted_at)` で被覆し、ローリング 12 ヶ月分の COUNT を数十行スキャンに収める（§3.5）。
- **公開表示のキャッシュ**: 認証マーク表示は読み取り頻度が高い。`state` を短 TTL（例 60 秒）でキャッシュし、状態遷移時にイベント（`TrustCertificationStateChangedEvent`）でキャッシュ無効化する。`@Cacheable` を使う場合は enum キーを String 化（`feedback_cacheable_enum_key_redis`）。
- **REVOKE 連鎖は 1 段・有界**: 連鎖を 1 段に制限（§3.7）することで、単一 REVOKE の処理量は「X の outgoing 信任数 + その被信任先の再評価」に有界化され、グラフ全体を巻き込むカスケードを防ぐ。
- **UUIDv7 主キー・テナント列**: 新規テーブルは UUIDv7（原則 6）。`trust_certifications`/`trust_endorsements` は `organization_id` を持ち `AbstractTenantAwareRepository` を実装（原則 7）。TEAM/ORG 両対応のため scope 派生 finder（escrow_transactions 前例の `scope_kind`＋`scope_id` 解決方式）を採る（[01 §3](01_data_model.md)）。**シャーディングについて正直な注記**: 本機能の主クエリ（incoming 有効件数・年間発行数の集計）は `scope_kind`＋`scope_id` 主導で走るため、`organization_id` をシャードキーにしても被覆されない（scope の org は endorser/endorsee で異なりうる）。分散化時は **scope キーでのルーティング、またはグローバルセカンダリインデックス（scope→シャードの対応表）が別途必要**であり、「organization_id シャーディングで自動的に shard-friendly」とは主張しない。`organization_id` はテナント集計・原則 7 準拠のために保持する。

---

## 10. 段階ロードマップ

依存と規模（S/M/L）を明示。各段は test-first（BE ドメイン UT ＋ API 契約テスト先行）。

| 段 | 名称 | 規模 | 依存 | 主要成果 |
|---|---|---|---|---|
| **P1** | 認証基盤＋アンカー | **M** | F00・F01.2 | `trust_certifications`/`trust_endorsements` DDL・`TrustErrorCode`・状態機械・アンカー付与（運営）・信任付与/取消（認証遷移 T1〜T8）・資格判定（§3.3） |
| **P2** | 連鎖・再審査キュー | **M** | P1 | REVOKE 連鎖（1 段・§3.7）・`UNDER_REVIEW` 再審査キュー・運営 API（REVOKE/再審査 OK/NG）・通知（§8） |
| **P3** | 公開表示 | **S** | P1・F00・F19.1 | 認証状態取得・信任関係一覧（公開・F00 PUBLIC 経由）・`TrustBadgeVisibility` ヘルパ・マッチング/F09.19/F19.1 への認証マーク表示結線 |
| **P4** | 認証マーク UI・i18n | **S** | P3 | 認証マークコンポーネント・信任関係公開表示・団体管理者の信任付与/取消 UI・運営レビューキュー UI・`trust.json` 6 言語 |

> 新規ドメイン: `trust`。改修: `notification`（受任/認証/降格の通常通知）・`publicview`/マッチング/`advertising`（認証マーク表示の消費）。F00 可視性は**再利用のみ**。

---

## 11. 要裁可論点（勝手に大方針を発明しない）

以下は設計上決めきれず、マスターの裁可を仰ぐ論点。各々**選択肢＋推奨**を提示する。

| # | 論点 | 選択肢 | 推奨 |
|---|---|---|---|
| **11-1** | **信任グラフの厳密な非巡回（acyclicity）を強制するか** | (a) 「信任元は認証済み」不変条件のみ（未認証リングは阻止できるが、認証済み同士の A→B・B→A や長い巡回は許容）／(b) 信任付与時にサイクル検出し巡回を拒否 | **(a) 推奨**。要件の狙い「未認証だけの信任リングで自己認証すること」は (a) で構造的に阻止できる。(b) はグラフ探索が必要で §9 の「探索不要」原則と 1000 万規模のコストに反する。「一方向 DAG」は厳密な数学的 DAG ではなく「アンカー起点で認証が外へ伝播する」意味と整理する（README の記述もこの解釈） |
| **11-2** | **資格の既定値（N ヶ月・M 人・年間上限）** | §3.3 の既定（6 ヶ月／5 人／10 件）は本設計の推奨値・実運用値は要確定 | **提示値を暫定採用**し、ベータ運用の実データで調整する前提。config で外部化済みなので後から無停止変更可 |
| **11-3** | **`UNDER_REVIEW` 団体は信任を発行できるか** | (a) `UNDER_REVIEW` も「認証済み扱い」で信任発行可（マーク表示中のため）／(b) `UNDER_REVIEW` は信任発行不可（`state=CERTIFIED` 厳密一致のみ発行可） | **(b) 推奨**。信任発行資格（§3.3）は `state=CERTIFIED`（＋アンカー）厳密一致とし、`UNDER_REVIEW`（信頼が揺らいでいる団体）には新規信任を発行させない。ただし §3.7 の連鎖で `UNDER_REVIEW` に落ちた団体が**過去に発行した**信任は 1 段制限で無効化しない（既存の被信任先は守る）。この非対称を README §3.7／状態遷移表に明記済 |
| **11-4** | **閾値 T=3 の変更可能性** | 確定値 3・ただし定数化 | **確定（3）＋定数化**（`mannschaft.trust.certification-threshold=3`）。運用変更の余地のみ残す |
| **11-5** | **1 団体が受けられる信任の上限（incoming 側）** | (a) 無制限（多いほど信頼が厚い）／(b) 表示上は 3 で頭打ち | **(a) 推奨**（incoming は無制限・件数は信頼の厚みとして公開表示。上限は発行側（outgoing）の年間上限のみ） |
| **11-6** | **endorsee 側の同意（受任の承諾）フローの要否** | (a) 同意不要（信任は endorser の一方向の対外表明・endorsee は存在検証のみ）／(b) endorsee 管理者の承諾を経て有効化／(c) (a)＋endorsee が特定の信任を自団体公開面から非表示にできる opt-out | **(a) 推奨・(c) を将来拡張点**。同意フローは付与の摩擦を上げ「信頼の輪」の自然拡大を阻害する。ただし「望まない団体から公開の信任を張られる」レピュテーション懸念は残るため opt-out を拡張点として温存（[03 §9.1](03_security.md)）。**関連脅威=存在オラクル**: 付与時の endorsee 検証を「実在すれば OK」にすると PRIVATE 団体の ID 総当り列挙に使われるため、(a) でも endorsee は「endorser 管理者から F00 可視」の場合のみ許可し、不可視・不在を同一応答 `TRUST_007` に統一する（[03 §3](03_security.md)・[02 §2.3](02_api_design.md)） |
| **11-7** | **PRIVATE 団体が当事者の信任の公開範囲** | (a) 要件どおり全公開（PRIVATE 団体名が公開面に露出）／(b) 安全側＝相手方が F00 可視（PUBLIC）の信任のみ公開面に出す（件数は全件） | **(b) 推奨・設計既定**（F19.1 の非公開原則と矛盾させない・[03 §4.1](03_security.md)） |

> 要裁可論点（11-1〜11-7）はいずれも**設計内に選択肢＋推奨を明示**した。実装着手（試練→出陣）前にマスター御裁可を得ること。11-2 の既定値は config 外部化により後から無停止で調整できるため、暫定値で試練を書き始めてよい。

---

## 12. 変更履歴

| 日付 | 内容 |
|---|---|
| 2026-07-08 | 初版起草。マスター合意済み要求仕様（呼称「信任」確定・NG語・閾値 3・一方向 DAG・アンカー・資格条件・状態機械・REVOKED 連鎖 1 段・公開表示 F00 経由・課金分離・F20.1 フック）を反映。origin/main 実物（F00 `AbstractContentVisibilityResolver`・payment `ScopeKind`・`UuidV7Entity`・`MembershipEntity`・V3.132/V3.133 `established_date`・F19.1 公開可視性）を一次ソースに設計。受け入れ条件 AC-01〜26・状態遷移表・要裁可論点 11-1〜11-5 を明示。ステータス 🟡 設計中（精査待ち） |
| 2026-07-08 | **精査第1パス反映**。(重大) 公開ゲートを実在の `ContentVisibilityChecker.canView(ReferenceType.TEAM|ORGANIZATION, scopeId, userIdOrNull)` へ差し替え（架空の PublicTeam/OrganizationVisibilityResolver を排除・§6/02 §6.1/03 §4.1）／アクティブメンバー数を既存 `MembershipRepository.countActiveDistinctUsersByScope` 再利用（在籍 DISTINCT・users.status 非連動）に確定（§3.3/02 §4.1）／団体削除カスケードをスコープ(in)・状態遷移 T12・AC-27・02 §5.4（`TeamDeletedEvent`/`OrganizationDeletedEvent` 実名）へ正式化。(中位) 公開 DTO の `UNDER_REVIEW` 丸め（02 §6.1/03 §4.2）・信任付与時の endorsee F00 可視性検証で存在オラクル封鎖（02 §2.3/03 §3）・シャード記述の正直化（§9/01 §1）・取消確認文言の通知矛盾修正（04 §6.2）・F20.3 依存方向の明記（§7）。(低) AC-04 平叙化・AC-08 Clock 固定明記・信任依頼フローをスコープ外に明記（§2.2）。AC-27〜34 追加（T5/T7/A3/A4/TRUST_008/TRUST_010/eligibility/アンカー解除）。`revoke_reason` に `ENDORSER_DELETED` 追加。要裁可論点（§11）は不変 |
