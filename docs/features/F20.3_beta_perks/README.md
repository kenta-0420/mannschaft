# F20.3 ベータ特典（活動実績ベースの無償エンタイトルメント付与）

> **ステータス**: 🟡 設計中（精査待ち）
> **最終更新**: 2026-07-08
> **親機能**: [F20.1 課金・エンタイトルメント基盤](../F20.1_entitlement_billing/README.md)（特典は `entitlements(source_kind='BETA_GRANT')` 行として発行する。本設計は F20.1 の機構を**消費するのみ**で判定ロジックを複製しない）
> **関連**: [F04.7 ゲーミフィケーション](../F04.7_gamification.md)（称号バッジは既存 `badges`/`user_badges` に system badge を追加・新機構を作らない）／ [F10.8 アクセス解析](../F10.8_team_org_access_analytics.md)（計測の最小連携先）

---

## ⚠️ 冒頭注記

1. **F00.6 ベータ登録制限とは別物**: `F00.6_beta_restriction` は「ベータ期間中に誰が登録できるか」の入場制御であり、本 F20.3 は「ベータ参加者への**特典（無償エンタイトルメント）**」である。混同禁止。
2. **「永久」の語は使用禁止**: 個人特典の文言は**「サービス提供期間中無償」**とする（設計書・実装・UI・規約すべて。「永久無料」「一生無料」等は法的リスクのため全面禁止）。
3. **単価・閾値は運用値**: 付与条件の判定基準は本設計で**機構として定義**するが、閾値（活動日数等）は運用値（マスタ `beta_perk_criteria`）であり、実値はベータ運用で調整する。

---

## 0. この設計書の構成

| ファイル | 内容 |
|---|---|
| `README.md`（本書） | 概要・特典二本立てモデル（個人/チーム・組織）・アカウント売買対策の 2 層・規約改訂要件・受け入れ条件・ロードマップ・要裁可論点・変更履歴 |
| [`01_data_model.md`](01_data_model.md) | DB設計（`beta_grants`／`beta_perk_criteria` の完全 DDL・entitlements との連結・review_flag 状態遷移・バッジ シード・Flyway 計画） |
| [`02_api_design.md`](02_api_design.md) | API設計（本人向け照会・自動付与バッチ・シスアド運用 API・更新（延長）・取消・審査解決・活動実績評価の擬似コード・DTO・エラーコード） |
| [`03_security.md`](03_security.md) | セキュリティ（認可マトリクス・アカウント売買対策・review_flag 運用・IDOR・GDPR/退会・ステータス確定条件） |
| [`04_ui_i18n.md`](04_ui_i18n.md) | 画面設計（マイベータ特典・チーム特典表示・シスアド審査画面）・i18n 6言語キー（`billing.json` の `betaPerks` セクション） |

---

## 1. 概要

ベータ期間（**第 1〜4 段階**・第 4 段階=1 万人規模）の参加者に、**活動実績**に応じて機能の無償利用権を付与する。

- 特典の実体は **F20.1 `entitlements` の `source_kind='BETA_GRANT'` 行**であり、付与メタ（誰に・いつ・どの条件で・どのフェーズで）を **`beta_grants`** テーブルに持つ。判定は常に F20.1 `isEntitled` を通る（特典専用の判定経路を作らない）。
- 付与対象の機能セットは**付与時点の FULL プラン構成（`plan_features('FULL')`）のスナップショット**。付与後に FULL へ追加された新機能は自動では含まれない（追加開放は運用判断でシスアドが延長/追加付与）。

### 1.1 特典の二本立て

| | **個人特典**（`grant_kind=INDIVIDUAL`） | **チーム/組織特典**（`grant_kind=TEAM_ORG`） |
|---|---|---|
| スコープ | **`scope_kind='USER'` 限定**（＝組織課金に充当不可・転売価値を構造的に潰す） | `scope_kind='TEAM'` または `'ORG'` |
| 期間 | `valid_until=NULL`（無期限）＝**「サービス提供期間中無償」**（「永久」不使用） | **最低 2 年間無償**（`valid_until = 付与日時 + 2 年` を下限）。以後の更新は**都度アナウンス＝自動更新しない** |
| 付与条件 | ベータ**参加ではなく活動実績**（機構は 02 §2・閾値は `beta_perk_criteria` の運用値） | 活動実績＋シスアド審査（手動付与） |
| 付与方法 | **日次バッチで自動付与**（第 4 段階 1 万人規模でも回る） | **シスアド手動付与**（件数少・審査を伴う） |
| 取消 | 可（`revoked_at`・規約違反時） | 可（同左） |
| 譲渡 | **禁止**（`transferable=false` 固定・CHECK 制約） | 同左＋**オーナー変更で review_flag**（§3） |
| 付随 | **称号バッジ**（F04.7 system badge・§4） | 付与時に**アクティブ人数バンドをスナップショット**（`memberships` の `left_at IS NULL` カウント・F20.1 01 §3.4 と同一定義） |

### 1.2 ベータ段階

`beta_phase TINYINT ∈ {1, 2, 3, 4}`。段階ごとに付与条件（`beta_perk_criteria`）を別に持てる（後の段階ほど条件を厳しくする運用を想定・値は運用値）。

---

## 2. 付与条件（活動実績・機構の定義）

**「参加しただけ」では付与しない**。判定基準（メトリクス）は機構として以下に固定し、**閾値のみ**を運用値とする（02 §2 に擬似コード）。

| メトリクス | 定義（正準） | データソース |
|---|---|---|
| `activeDays` | 評価ウィンドウ内のアクティブ日数 = **`COUNT(DISTINCT DATE(created_at))`**（本人のログイン成功ログ） | **`audit_logs` の `LOGIN_SUCCESS`**（`AuditEventType.LOGIN_SUCCESS`・`user_id = 対象`・月次パーティション）。※F10.8 `page_view_logs` は **TEAM/ORGANIZATION スコープ限定で USER を持たない**ため使えない（§7・§9.1 で確定）。「閲覧ビーコン」は本メトリクスの源ではない |
| `membershipTenureDays` | **INDIVIDUAL=本人の最古有効所属 `memberships.joined_at` 最小値（`left_at IS NULL` 行）からの経過日数** ／ **TEAM_ORG=スコープ自体の作成日（`teams.created_at`/`organizations.created_at`）からの経過日数**（02 §2 と両建て一致） | `memberships`（INDIVIDUAL）／`teams`・`organizations`（TEAM_ORG） |
| `activeMembers`（TEAM_ORG のみ） | アクティブ人数（`countActiveDistinctUsersByScope`・DISTINCT user・F20.1 01 §3.4） | `memberships` |

- 閾値（例: `activeDays >= 14`／`membershipTenureDays >= 30`／`activeMembers >= 5`）はマスタ `beta_perk_criteria`（beta_phase×grant_kind ごと）に保持し、シスアドが運用変更できる。
- 判定時の**実測値と閾値を `beta_grants.criteria_snapshot`（JSON）に焼き付け**る（後から「何を満たして付与されたか」を監査可能に・遡及不能）。

> **★自動付与の本番有効化条件（③・「参加しただけでは付与しない」主原則の担保）**: `membershipTenureDays`（在籍日数）だけで自動付与すると「在籍 30 日の完全無活動ユーザー」に付与され得て、主原則違反かつ売買対策の前提（活動実績）が崩れる。よって **`activeDays` 計測源（`audit_logs` LOGIN_SUCCESS）の結線を自動付与バッチ本番有効化の前提条件**とする。**`min_active_days=NULL`（activeDays 未計測）のまま自動付与バッチを本番有効化しない** — その tenure-only 期間は**シスアド審査付きの手動付与のみ**で運用する（02 §3・§9 実装前確定条件）。

---

## 3. アカウント売買対策（2 層・主防壁は構造）

| 層 | 内容 |
|---|---|
| **主防壁（構造）** | 個人特典は **USER スコープ限定**＝チーム/組織の課金に充当できず、アカウントを買っても**買い手の団体運営は安くならない**。転売の経済価値を設計で潰す |
| 保険 1（規約） | 特典は**規約上取消可能**（譲渡・売買・貸与の禁止と違反時取消を規約に明記・§5 規約改訂要件） |
| 保険 2（検知） | 所有者変更の兆候で **`review_flag`** を立て運営審査に回す（チーム/組織特典: **オーナー（ADMIN）変更イベント**で自動フラグ。02 §5）。**検知は保険であり主防壁ではない**（誤検知は審査で解消・フラグ中も権利は有効のまま） |

---

## 4. 称号バッジ（F04.7 流用・新機構を作らない）

- 既存 `badges` / `user_badges`（F04.7）に **system badge** を追加する。**実在スキーマ（origin/main 実確認・2026-07-08）**:
  - `badges`: `scope_type VARCHAR(50) NOT NULL`／`scope_id BIGINT UNSIGNED NOT NULL`／`name VARCHAR(100) NOT NULL`／`badge_type VARCHAR(50) NOT NULL`／`condition_type VARCHAR(50) NOT NULL`／`is_system TINYINT(1) DEFAULT 0`／`is_active TINYINT(1) DEFAULT 1`／`icon_emoji VARCHAR(10)`／論理削除 `deleted_at`。
  - **`BadgeType` enum の実値は `{STANDARD, MILESTONE, SPECIAL}`**（`com.mannschaft.app.gamification.BadgeType`・実確認）。**`PERFECT_ATTENDANCE`/`MVP`/`POST_MASTER`/`STREAK`/`CUSTOM` は誤り（架空）だった** — 訂正する。`condition_type` の `MANUAL` は実在（正しい）。
  - `user_badges`: `period_label VARCHAR(20)`／`awarded_by VARCHAR(20) NOT NULL`／`UNIQUE uq_ub_badge_user_period (badge_id, user_id, period_label)`。
- **本機能の追加方針（enum に新値を足さない）**: `badge_type` は**種別カテゴリ**であり個別バッジ識別子ではないため、ベータテスター称号は **`badge_type='SPECIAL'`・`is_system=TRUE`・`condition_type='MANUAL'`** の badges 行を 1 つ Flyway シードする（`V11.053` の様式・01 §5）。バッジの識別は行そのもの（`id`/`name`）で行い、**`BadgeType` enum は変更しない**。
- **フェーズ別の称号は `user_badges.period_label = 'BETA_PHASE_1'〜'BETA_PHASE_4'`** で区別（バッジ行は 1 つ・`uq_ub_badge_user_period` がフェーズ別の重複授与を物理防止）。`period_label` は VARCHAR(20) ゆえ `BETA_PHASE_4`（12 文字）は収まる。
- **⚠️ scope NOT NULL 制約の未決点（実装前確定条件・要裁可 B-5）**: `badges.scope_type`/`scope_id` は **NOT NULL** であり、既存 badges はチーム/組織のゲーミフィケーションに紐づく。**プラットフォーム横断の system badge を置く前例（scope 無し）は origin/main に存在しない**。ベータ称号は全ユーザー共通のためスコープに属さない。→ **sentinel scope（`scope_type='PLATFORM'`・`scope_id=0`）で 1 行シードし、授与/表示経路をこの sentinel に対応させる**方針を推奨するが、gamification の既存クエリ（`findBy...ScopeTypeAndScopeId`）・表示各所への波及があるため、**実装前に gamification ドメインの scope 取り扱いを再確認して確定**する（B-5・§9）。（※「1 スコープ 50 バッジ上限」に相当する制約は origin/main で**実在未確認**のため、波及懸念としては断定しない。実在するのはカスタムルール上限等の別制約であり、実装前に該当上限の有無を調査する。）
- 授与タイミング: 個人特典の付与成功と同時（`awarded_by='SYSTEM'`）。バッジは特典取消後も**剥奪しない**（活動実績の称号であり権利ではない・要裁可論点 B-3）。

---

## 5. 規約改訂要件（利用規約との整合）

現行規約（`frontend/app/locales/ja/landing.json` の `landing.legal.terms.sections`・i18n 6 言語格納・実確認済 2026-07-08）に対して:

1. **第 26 条は「言語」条項**（日本語正文優先）であり、特典とは無関係（当初仕様の「26 条と整合」は事実誤認として訂正）。
2. **一般受け皿は第 17 条（権利義務の譲渡禁止・事業譲渡）**:
   > 「利用者は、当社の書面による事前の承諾なく、利用契約上の地位または本規約に基づく権利義務を、第三者に譲渡し、承継させ、または担保に供することはできません。」
   — ベータ特典（本規約に基づく権利）の譲渡禁止はこの条文で**現行でも一応担保される**。
3. **ベータ特典専用条項は現規約に存在しない → 新設が必要（規約改訂要件）**。盛り込むべき要素:
   - (a) 特典が**無償提供**であり、提供内容・期間を当社が定めること（個人=「サービス提供期間中無償」・チーム/組織=最低 2 年＋以後は都度アナウンス）。
   - (b) 特典の**譲渡・売買・貸与の禁止**（第 17 条の特則として明示）。
   - (c) **違反時・不正取得時の取消**（当社は特典を取り消せる）。
   - (d) 「永久」「無期限の保証」と誤認させる表現をしない旨（表示ルール）。
   - 条文実体は `landing.json` の `landing.legal.terms.sections`（6 言語）への追補として実装。**条番号は現行最終条の次で仮置きし、規約改訂 PR で確定**する。規約改訂はマスター承認事項（本設計は要件の記録まで・要裁可論点 B-1）。

---

## 6. 受け入れ条件（AC）

| # | 区分 | 誰が・何をしたら・どうなる（観測可能） |
|---|---|---|
| AC-01 | 正常 | 付与条件（criteria）を満たす個人に自動付与バッチが走る → `beta_grants(INDIVIDUAL)` 1 行＋ FULL 構成分の `entitlements(source_kind=BETA_GRANT, scope_kind=USER, valid_until=NULL)` が発行され、本人に通知が届く |
| AC-02 | 境界 | 個人特典保持者 U がチーム T の TEAM スコープ操作（有料機能）を行う → **402**（個人特典は USER スコープ限定＝組織課金に充当不可） |
| AC-03 | 異常 | 条件未達のユーザーへのシスアド手動付与（強制フラグなし） → **422 `BETA_PERK_003`**（実測値と閾値を details に含む） |
| AC-04 | 正常 | チーム特典付与 → `entitlements.valid_until = 付与日時 + 2 年`（下限）で発行される |
| AC-05 | 正常 | チーム特典付与時、`beta_grants.active_member_count_snapshot` に付与時点の `memberships`（`left_at IS NULL`）件数が焼き付く |
| AC-06 | 異常 | `transferable=true` での INSERT/UPDATE → CHECK 制約違反（DB レベルで物理拒否） |
| AC-07 | 正常 | チームのオーナー（ADMIN）変更イベント発生 → 当該チームの有効な `beta_grants.review_flag=true`・`review_reason='OWNER_CHANGED'` になり、運営に通知が届く |
| AC-08 | 境界 | review_flag 中も `isEntitled` は **true のまま**（フラグは審査待ちであって停止ではない） |
| AC-09 | 正常 | シスアドが取消（revoke） → `beta_grants.revoked_at`＋由来 entitlements 全件 revoke → 対象機能が即（キャッシュ evict 後）402 に戻る |
| AC-10 | 異常 | 同一（scope×beta_phase）への二重付与 → **409 `BETA_PERK_002`** |
| AC-11 | 正常 | 個人特典付与と同時に `user_badges` へベータテスター system badge（`badge_type='SPECIAL'`・`period_label='BETA_PHASE_{n}'`・`awarded_by='SYSTEM'`）が授与される。同フェーズ二重授与は `uq_ub_badge_user_period` で防止 |
| AC-12 | 正常 | ゲート通過した機能利用が F10.8 `page_view_logs` に `content_type='FEATURE'`・`title=feature_key` で記録される（§7） |
| AC-13 | 文言 | 個人特典の UI/通知/設計書に「永久」の語が出現しない（表示は「サービス提供期間中無償」）。grep 検分対象＝`frontend/app/locales`・`backend/src/main/resources/messages*`・**`docs/features/F20*`**・**`landing.json`**（03 §8） |
| AC-14 | 正常 | チーム特典の 2 年満了: 自動更新されず `valid_until` 到来で自然失効（`isEntitled=false`）。シスアドの延長操作で**新 entitlement 行**が発行され継続する |
| AC-15 | 異常 | `beta_phase` に 1〜4 以外を指定 → **400 `BETA_PERK_004`** |
| AC-16 | 異常 | `grant_kind=INDIVIDUAL` × `scope_kind≠USER`（または TEAM_ORG × USER）の付与 → **422 `BETA_PERK_007`** |
| AC-17 | 正常 | 本人が `GET /me/beta-perks` で自分の特典・充足状況（実測値/閾値）を確認できる。**他人の特典は見えない** |
| AC-18 | 異常 | 一般ユーザーがシスアド運用 API（付与・取消・審査）を呼ぶ → 403 |
| AC-19 | 正常 | 個人特典保持者の退会**確定（purge）** → `AccountPurgedEvent` 受信で grant は revoke され entitlements も失効（申請・猶予中は revoke されず権利維持・撤回で復帰。§8） |
| AC-20 | 境界 | 審査解決（問題なし） → `review_flag=false`・`review_resolved_at/by` 記録・権利は連続して有効 |

---

## 7. 計測（ベータ中の課金判断用データ・F10.8 最小連携）

- **方式（TEAM/ORG 利用の傾向計測）**: **FE ビーコン流用の最小連携**とする。F10.8 の収集 API `POST /api/v1/page-views`（既存・未認証許容・匿名 cookie・`@Async`）の **content type に 1 値 `FEATURE` を追加**する（`page_view_logs.content_type` は `VARCHAR(20)`・DB CHECK なしゆえ **DDL 不要**。許容外 400 `TEAMANALYTICS_003` の enum バインドに 1 値追加）。
  - **enum 名は未確定**: F10.8 設計書には content type を束ねる enum の**確定した名前が無い**（設計は列挙値のみ提示）。よって本書は enum 名を `PageViewContentType` と決め打ちせず、**「F10.8 実装時に命名される content type enum に `FEATURE` を 1 値追加する」**と記す（実装時に F10.8 側の実 enum 名へ合わせる）。
  - 送信規約: ゲート対象機能の利用成功時に FE が `{ scope, scopeId, contentType: 'FEATURE', contentId: 0, url: <発火元パス>, title: <feature_key> }` を送る（`content_id` は BIGINT のため **0 固定**・feature_key は `title` に載せる。`PAGE` の `content_id=0` 既存前例に整合）。
- **⚠️ 個人（USER）の activeDays は F10.8 では計測不能（実装前確定条件・§9）**: F10.8 の `page_view_logs.scope_type` は **`TEAM`/`ORGANIZATION` のみ**で **USER スコープを持たない**。よって個人特典の付与条件 `activeDays`（本人の閲覧日数）は F10.8 経由では**構造的に計測できない**。代替源として origin/main に実在するもの:
  - **`users.last_login_at`（`UserEntity.lastLoginAt`・実在）** — 最終ログインのみで日別履歴は取れない（`activeDays` の近似には不十分）。
  - **`audit_logs` の `LOGIN_SUCCESS`（`AuditEventType.LOGIN_SUCCESS`・実在・月次パーティション）** — 日別のログイン成功を `COUNT(DISTINCT DATE(created_at))` で数えられる＝**`activeDays` の実データ源として最有力**。
  - **gamification `point_transactions` の `DAILY_LOGIN`（実在）** — 日次ログインポイントの付与履歴で日別在籍を代替できる。
  - → **`activeDays` の計測経路（audit_logs LOGIN_SUCCESS を第一候補とする）を実装前に確定**する（§9 実装前確定条件）。それまでは `beta_perk_criteria.min_active_days=NULL` 運用で `membershipTenureDays` のみで自動付与を成立させる（02 §2・§3）。
  - **根拠**: 利用イベント 1 種の追加で済み、収集・保持・パーティション・匿名化の基盤を再発明しない。FE ビーコンの取りこぼしは傾向データ用途で許容。BE 側の正確な二重記録は Phase 2 拡張。
- **人数分布**: `beta_grants` のスナップショット＋`memberships` のアドホック集計で足りる（専用テーブルなし）。
- **集計ダッシュボードは F20.1 側の将来拡張**（Phase 2）であり本設計では作らない。

---

## 8. 段階ロードマップ

| 段 | 名称 | 規模 | 依存 | 主要成果 |
|---|---|---|---|---|
| **P1** | 付与機構＋シスアド運用 | **M** | F20.1 P1 | `beta_grants`/`beta_perk_criteria`・手動付与/取消/審査 API・バッジシード・規約改訂要件の確定 |
| **P2** | 自動付与バッチ＋計測 | **M** | P1・F10.8 実装 | 日次自動付与バッチ・F10.8 `FEATURE` ビーコン・本人向け照会/FE |
| **P3** | 審査運用強化 | **S** | P1 | オーナー変更イベント連携・review 運用画面 |

> F10.8 は設計完了・実装未着手（2026-07-08 現在）。P2 の `activeDays` メトリクスと `FEATURE` ビーコンは **F10.8 実装後**に接続する（それまで自動付与は `membershipTenureDays` のみで運用可能な設計とする・02 §2）。

---

## 9. 要裁可論点（マスター御裁可待ち）

| # | 論点 | 選択肢 | 推奨 |
|---|---|---|---|
| **B-1** | 規約改訂（ベータ特典専用条項の新設・§5） | 条文案を規約改訂 PR で提示 | §5 の 4 要素で起草しマスター承認後に 6 言語反映 |
| **B-2** | チーム/組織特典の 2 年後更新の運用 | (a) シスアド一括延長操作のみ／(b) 自動更新オプトイン | **(a)**（「自動更新しない」がマスター確定事項。延長は都度アナウンス→一括操作） |
| **B-3** | 特典取消時のバッジ剥奪 | (a) 剥奪しない／(b) 剥奪する | **(a)**（バッジは活動実績の称号で権利ではない。不正取得と断定された場合のみ手動剥奪を運用で許す） |
| **B-4** | オーナー変更イベントの実装 | team ドメインに `TeamOwnershipTransferredEvent` が現存しない場合、新設 publish が必要 | team ドメイン側に最小のイベント publish を追加（クロスドメインはイベント駆動の原則どおり）。実装時に既存イベント有無を再確認 |
| **B-5** | ベータ称号 system badge の scope 取り扱い（§4） | `badges.scope_type/scope_id` NOT NULL・プラットフォーム横断 badge の前例なし | sentinel scope（`PLATFORM`/`0`）で 1 行シード＋授与/表示経路対応を推奨。実装前に gamification の scope クエリ・50 バッジ上限・表示波及を再確認して確定 |

### 9.1 実装前確定条件（設計はブロックしないが実装着手前に決める）

| 条件 | 内容 | それまでの運用 |
|---|---|---|
| **個人 activeDays の計測源** | F10.8 は USER スコープ非対応（§7）。`audit_logs` の `LOGIN_SUCCESS` を第一候補に日別在籍を数える経路を確定する | `min_active_days=NULL` で `membershipTenureDays` のみで自動付与（02 §2・§3） |
| **F10.8 content type enum への `FEATURE` 追加** | enum 名は F10.8 実装時に確定（§7）。TEAM/ORG 利用の傾向計測用 | F10.8 実装完了まで機能利用計測は保留（自動付与判定には不使用ゆえブロックしない） |
| **ベータ称号の scope 取り扱い（B-5）** | §4 の sentinel scope 方針を gamification 実装と突き合わせて確定 | 特典付与自体はバッジ授与に依存しない（授与失敗は補助チャネルとして握って継続・付与本体は成立） |

---

## 10. 変更履歴

| 日付 | 内容 |
|---|---|
| 2026-07-08 | 初版。マスター合意済み要求仕様（特典= BETA_GRANT entitlement・個人=活動実績×USER 限定×「サービス提供期間中無償」・チーム/組織=最低 2 年×人数バンドスナップショット×オーナー変更 review_flag・売買対策 2 層・F10.8 最小連携・F04.7 バッジ流用）を反映して起草。規約は実確認（第 26 条=言語条項・第 17 条=譲渡禁止の一般受け皿・特典専用条項なし→改訂要件化） |
