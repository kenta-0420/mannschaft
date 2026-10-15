# F10.1.1 / 04: 認可・セキュリティ設計

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-06-17
> **関連**: [docs/security/01_authorization_baseline.md](../../security/01_authorization_baseline.md) / [F00_content_visibility_resolver.md](../F00_content_visibility_resolver.md) / [README.md](./README.md)

---

## 1. 認可の基本方針

本機能は新しい認可基盤を作らない。既存の以下を流用する（メソッドシグネチャは R7 偵察で実在確認済み）:

| 用途 | 流用する基盤（実在） | 配置 |
|------|------------|------|
| ADMIN/DEPUTY 判定（強制） | `AccessControlService.checkAdminOrAbove(userId, scopeId, scopeType)` | `com.mannschaft.app.common` |
| ADMIN/DEPUTY 判定（真偽・集合判定） | `AccessControlService.isAdminOrAbove(userId, scopeId, scopeType)` | 同上 |
| ADMIN のみ判定（DEPUTY 除外） | `AccessControlService.isAdmin(userId, scopeId, scopeType)` | 同上 |
| 実効ロール解決（両系統統合） | `AccessControlService.resolveEffectiveRoleName(userId, scopeId, scopeType)` | 同上 |
| 権限グループ判定（ORG 専用） | `AccessControlService.checkAdminOrHasPermission(userId, scopeId, "ORGANIZATION", perm)` | 同上 |
| 権限保有真偽 | `AccessControlService.hasPermission(userId, scopeId, scopeType, perm)` | 同上 |
| 可視性ラダー | `StandardVisibility.ADMINS_AND_ABOVE`（ADMIN+DEPUTY 包含） | `com.mannschaft.app.common.visibility` |
| FE 表示制御（最終判断ではない） | `useRoleAccess(scopeType, slug).isAdminOrDeputy` | `frontend/app/composables/useRoleAccess.ts` |

> 可視性は `min_role`（3値 enum）ではなく `StandardVisibility.ADMINS_AND_ABOVE` をコード固定で用いる。理由は [02](./02_admin_lens_widgets.md) §2.1（`MinRole` enum は3値のみで `ADMINS_AND_ABOVE` を持たないため）。

---

## 2. isAdmin 常時 true 禁止・サーバが最終判断

- **FE はあくまで表示制御**。`useRoleAccess` で取得したロールは「誤遷移の早期遮断」「ボタンの出し分け」にのみ使う（`useRoleAccess(scopeType, slug)`・第2引数は slug 文字列・R8）。
- **すべての管理 API は BE で必ず `checkAdminOrAbove`（または権限グループ判定）を入口で通す**。本機能で新設する API（[03](./03_admin_action_required_api.md) の `admin-action-required`）はファサード入口で `checkAdminOrAbove` を呼ぶ。既存流用 API（予約・予算・メンバー）は各 Controller/Service が既に同等の認可を持つため、それを温存する。
- FE の `admin-console` ミドルウェア（[01](./01_console_routes.md) §5）の遮断は UX 上の早期弾きであり、これだけに依存しない。仮に FE 判定を改ざんしても、BE が 403 を返すため情報は漏れない。
- `isAdmin` をハードコードで true にする・テストの便宜で常時許可する等は禁止（メモリ `feedback_visibility_bypass_f00_audit`）。

### 2.1 SYSTEM_ADMIN の扱い（FE トグル表示と BE 認可の食い違い解消）【C・SYSTEM_ADMIN 整合 根治】

> **偵察で判明した食い違い（R7/R8）**: FE の `useRoleAccess.isAdminOrDeputy` は **SYSTEM_ADMIN でも true**（`isAdmin = roleName==='ADMIN' || roleName==='SYSTEM_ADMIN'`）。一方 BE の `AccessControlService.checkAdminOrAbove` / `isAdminOrAbove` は `getRoleName`（scope 内ロール）の結果を `ADMIN_ROLES = {"ADMIN","DEPUTY_ADMIN"}` で判定するため、**SYSTEM_ADMIN が当該 scope に ADMIN/DEPUTY ロールを持たない場合は false（403）**になる。FE はトグルを出すのに BE が API を 403 で弾く、という不整合が起きうる。

**確定ポリシー**: 「SYSTEM_ADMIN は scope 内ロールを別途持たない限り、チーム/組織の管理コンソールでは管理者として扱わない」。

- **BE**: 本機能の管理 API は `checkAdminOrAbove`（scope 内 ADMIN/DEPUTY のみ・SYSTEM_ADMIN を scope 管理者と見なさない）を入口に置く。SYSTEM_ADMIN がプラットフォーム横断で操作する用途は F10.1 区分 B（`/system-admin`）の責務であり、本コンソール（区分 A）の対象外（[README](./README.md) §2）。`me/permissions` の `roleName` が SYSTEM_ADMIN でも scope ロールが無ければ本 API は 403 を返す。
- **FE**: `me/permissions` EP の `roleName` は scope 内の実効ロールを返す。SYSTEM_ADMIN がその scope に ADMIN/DEPUTY を持たない場合、`me/permissions` は scope ロール（`null`/`MEMBER` 等）を返すため `isAdminOrDeputy` は false になり、トグルも `admin-console` 通過も発生しない。**この前提（`me/permissions` が scope 実効ロールを返す）を満たす限り FE と BE は一致する**。
- 念のため FE 側のレンズトグル表示条件は「`isAdminOrDeputy` かつ `roleName !== 'SYSTEM_ADMIN'` ではなく」**`me/permissions` の scope 実効 roleName が ADMIN/DEPUTY_ADMIN であること**に揃える（SYSTEM_ADMIN がグローバル権限だけで scope ロールを持たない場合はトグルを出さない）。これにより BE 403 とのちぐはぐ（トグルは出るが API で弾かれる）を根絶する。

---

## 3. 可視性は F00 正準ラダー経由

- 管理者ウィジェット・管理コンソールの可視性ラダーは **`StandardVisibility.ADMINS_AND_ABOVE`**（正準ラダーの ADMIN+DEPUTY 段。R7 で包含確認）を使う。独自の `visibility` 述語・独自ロールゲートを新設しない（メモリ `feedback_visibility_bypass_f00_audit`）。
- **管理者ウィジェット（`ADMIN_*`）は F02.2.1 の `min_role`（3値）管理対象外**とする（[02](./02_admin_lens_widgets.md) §2.1）。既存の ADMIN 限定ウィジェット（`TEAM_BILLING` / `ORG_BILLING` / `TEAM_PAGE_VIEWS`。`WidgetKey.ROLE_RESTRICTED`・`isConfigurable()=false`）と同じ扱いに揃え、コードで `ADMINS_AND_ABOVE` 固定。DB の min_role 列に格納したり UI 設定で PUBLIC/SUPPORTER/MEMBER に下げたりはできない。F02.2.1 §1「管理者ウィジェットの min_role」追記もこの扱い（min_role 管理対象外・コード固定）と矛盾しないよう改訂する（[05](./05_decisions.md) §7・F02.2.1 改訂）。
- サーバ側の可視判定は集合判定 `isAdminOrAbove`（DEPUTY 含む）を用いる（[02](./02_admin_lens_widgets.md) §4.1）。false のウィジェットはレスポンスから省略する（データを送らない）。

---

## 4. DEPUTY_ADMIN の細粒度権限

### 4.1 原則

`ADMINS_AND_ABOVE` は ADMIN・DEPUTY_ADMIN を含む。DEPUTY に常時開放してよい管理機能（予約承認・メンバー一覧・承認待ち閲覧）と、ADMIN 専用または権限グループでの明示付与が必要な機能（課金・予算・モジュール ON/OFF・設定変更）を分ける。

| セクション | ADMIN | DEPUTY_ADMIN |
|-----------|:-----:|:------------:|
| 予約確認・承認待ち閲覧・メンバー一覧 | ✅ | ✅（ADMINS_AND_ABOVE で自動） |
| 予算閲覧 | ✅ | 権限グループ `BUDGET_VIEW` 保有時のみ |
| 予算編集（配分/取引/締め） | ✅ | 権限グループ `BUDGET_MANAGE` 保有時のみ |
| ロール変更 | ✅ | 不可（ADMIN 専用） |
| 設定変更（faq/public/shift 等） | ✅ | 各設定に対応する権限グループ保有時のみ |
| モジュール ON/OFF | ✅ | 不可（ADMIN 専用） |
| ポイントカード押印（組織） | ✅ | `POINT_CARD_STAMP_ISSUE` 保有時（既存） |

### 4.2 実装ゲート

- **組織スコープ**: `accessControlService.checkAdminOrHasPermission(userId, orgId, "ORGANIZATION", "BUDGET_VIEW")`（ADMIN 無条件・DEPUTY は権限保有時）。これは既存メソッド（R7 で ORGANIZATION 専用と確認）。
- **チームスコープ**: `checkAdminOrHasPermission` は実コードで **ORGANIZATION 専用（TEAM を渡すと `IllegalArgumentException`）**（R7 確定）。よってチームでは `isAdmin(userId, teamId, "TEAM")` ∨ `hasPermission(userId, teamId, "TEAM", "BUDGET_VIEW")` を明示的に組む。
- ADMIN 専用機能（ロール変更・モジュール ON/OFF）は `checkAdminOrAbove` ではなく `isAdmin`（DEPUTY を除外する真の ADMIN 判定）で守る。

### 4.3 パーミッション定数とスコープの実態（新設可否を断定）【C・budget 権限の実在 / 宙ぶらりん 根治】

| 定数 | 用途 | 実在（R5 偵察） | scope | 新設要否 |
|------|------|:--------------:|------|---------|
| `BUDGET_VIEW` | 予算サマリ・一覧の閲覧 | **実在**（Flyway `V11.034`） | **ORGANIZATION** | 組織スコープは新設不要 |
| `BUDGET_MANAGE` | 予算の編集 | **実在**（同上） | **ORGANIZATION** | 組織スコープは新設不要 |
| `POINT_CARD_STAMP_ISSUE` | ポイント押印 | 実在 | ORGANIZATION | 不要 |

**チームスコープの予算ウィジェット DEPUTY ゲートに関する確定**:

`BUDGET_VIEW` / `BUDGET_MANAGE` は現状 **scope=ORGANIZATION でのみ seed** されている（R5）。チームの予算ウィジェット（`ADMIN_TEAM_BUDGET`）で `hasPermission(userId, teamId, "TEAM", "BUDGET_VIEW")` を判定するには、**`BUDGET_VIEW` / `BUDGET_MANAGE` を scope=TEAM でも seed する Flyway マイグレーションが必要**になる。

- これは「マイグレーションなし」では成立しない。本機能は **チームスコープ向け `BUDGET_VIEW` / `BUDGET_MANAGE` の seed を追加する Flyway マイグレーションを伴う**（README §6 注記を訂正・後述）。
- Flyway 採番は「origin/main 全体の最大 major + 1」に従い、マージ直前に再確認する（CLAUDE.md / メモリ `feedback_flyway_version_sort_after_global_max` / `feedback_migration_version_collision`）。seed 形式は F02.2.1 §9 の `DASHBOARD_WIDGET_VISIBILITY_MANAGE` seed（permissions テーブル: `name / display_name / scope / created_at / updated_at`、role_permissions に ADMIN=`is_default=1` / DEPUTY=`is_default=0`）を手本にする。
- DEPUTY のチーム予算閲覧を「権限グループで明示付与」できるようにするのが本機能の要件のため、この seed は**本機能の必須依存タスク**として起票する（[05](./05_decisions.md) §8）。「将来一般化の余地」では済ませない。

> 別案として「チームの予算ウィジェットでは DEPUTY 細粒度ゲートを行わず ADMIN 専用にする（`isAdmin` のみ）」も検討したが、要件（DEPUTY に権限付与で予算を見せられる）を満たさないため却下。チームスコープ seed を追加して要件を満たす（[05](./05_decisions.md) §3）。

> **⚠️ 注記（実装軍議で判明）**: `permissions.name` は単独 UNIQUE（`uq_permissions_name`・`V2.002`）のため、同名 `BUDGET_VIEW`/`BUDGET_MANAGE` の TEAM 行 seed は不可。TEAM 予算権限の最終方針は [05_decisions.md §13](./05_decisions.md) のとおり **P3 の軍議で確定**（P1 スコープ外）。

---

## 5. IDOR・テナント越境の防止

- 管理 API はすべて `scope_type + scope_id` で絞り込む。`scope_id` はパスパラメータで受けるが、**認可は `SecurityUtils.getCurrentUserId()` の所属＋ロールで判定**する（リクエストボディの userId は信用しない）。
- 他組織/他チームの `scope_id` を与えても、`checkAdminOrAbove(currentUserId, otherScopeId, scopeType)` が当該ユーザーの非所属を検知して 403 を返す → 他テナントのデータは一切返らない。
- 集約 API（[03](./03_admin_action_required_api.md)）の各ドメイン Query Service も、件数 `COUNT` の WHERE 句に必ずスコープ列（`team_id` / `issuer_scope_id`）を含める。テナント絞り込みリポジトリの継承実態と依存タスクは [03](./03_admin_action_required_api.md) §5.1 の表で断定済み（reservation/shift/matching は team 専用ドメインで `organization_id` 単一列方式でないため `AbstractTenantAwareRepository` の対象外、payment は `scope_kind/scope_id` 方式で基底クラス形に非合致。いずれも入口認可＋WHERE スコープ絞り込みの二重で IDOR を防ぎ、本機能で番人テストを課す）。
- FE の `admin-console` ミドルウェアは別組織 slug でのアクセスをスコープトップへリダイレクトして弾く（[01](./01_console_routes.md) §5・404 ではなくプロジェクト慣習に整合）。

---

## 6. 退会・匿名化（§13.12）と PII

- メンバー管理画面（[01](./01_console_routes.md) §3.4）は退会済み・退会申請中メンバーを表示しうる。表示名は `UserService.getDisplayNames` 経由で取得し、状態別に以下のとおり扱う:

| メンバー状態 | getDisplayNames が返す値 | 管理コンソールでの表示・区別・操作可否 |
|-------------|------------------------|--------------------------------|
| 在籍 | 実表示名 | 通常表示。ロール変更・招待等の操作可 |
| **退会申請中（撤回可能・§13.12 の即時消去フェーズ実施後／強匿名化30日待ち中）** | **弱匿名化後の値**（通知・お気に入り等の即時消去済み。氏名等は強匿名化前なら残存しうるが、本コンソールでは getDisplayNames が返す現時点の値をそのまま表示し、原 PII を復元しない） | 「退会手続き中」のバッジを付けて在籍メンバーと**視覚的に区別**する。ロール変更・招待など状態を変える操作は**不可**（退会フロー進行中のため。一覧表示と statistics 計上のみ） |
| 退会済み（強匿名化完了） | 匿名表示名（`anonymize()` 済み） | 匿名表示名をそのまま表示。原 PII を復元・特別表示しない。操作不可 |

- §13.12 の二段モデル（即時消去＝弱匿名化／最大30日後＝強匿名化）において、「退会申請中（撤回ウィンドウ中）」のメンバーは中間状態である。本コンソールは getDisplayNames が返す**その時点の値**を表示し、PII を能動的に復元しない。退会申請中の判定は退会フロー（`requestWithdrawal` 受付済み・`AccountPurgeService` 未完了）の状態で行い、メンバー一覧 API が状態フラグ（`withdrawalPending`）を返す前提とする（F10.1 母体の `admin/dashboard/users` 応答に状態を含める。無ければ当該ドメインに状態露出を依頼＝本機能の依存タスク・[05](./05_decisions.md) §8）。
- 本機能は新規 PII テーブルを作らないため、退会フロー（即時/猶予の二段モデル）への新規追加対応は不要。L1 レンズの `adminLens` 状態は `localStorage`（F22.1 既存キー `scope-dashboard` に同梱）のみで DB 保存しない（PII でも DB データでもない・[02](./02_admin_lens_widgets.md) §1.2）。

---

## 7. レートリミット・監査ログ

- 集約 API（GET・読み取り）は管理 API の既存レート（`/admin/**` 1分間60件相当）の枠で運用。読み取りのため厳格な個別制限は不要。
- 承認・却下の**実操作は各ドメインの既存 API が既存の監査ログ**（`audit_logs`）を記録する。集約 API は読み取りのため監査ログを発行しない。
- L1 管理者レンズのトグル切替は監査対象外（クライアント表示状態）。

---

## 8. セキュリティ・チェックリスト（検分用）

- [ ] 新設 `admin-action-required` API がファサード入口で `checkAdminOrAbove` を通す
- [ ] スコープ別動的ドメイン（team=予約/シフト/マッチング、org=支払）で集約し、無効ドメインを配列に含めない
- [ ] 各ドメインの Query Service が WHERE にスコープ列（`team_id`/`issuer_scope_id`）を含む（テナント越境なし・番人テスト）
- [ ] 縮退は一時障害（DataAccessException/Timeout）のみ・認可例外/プログラミングエラーは伝播（症状を隠さない・`degraded` で 0 件と区別）
- [ ] 管理者ウィジェットの可視性は `ADMINS_AND_ABOVE` コード固定（min_role 管理対象外・`isConfigurable()=false`）、判定は集合判定 `isAdminOrAbove`（DEPUTY 含む）
- [ ] DEPUTY 細粒度ゲート（BUDGET_VIEW 等）が ORG は `checkAdminOrHasPermission`、TEAM は明示判定＋TEAM スコープ seed 追加
- [ ] FE `admin-console` ミドルウェアが権限不足をスコープトップへリダイレクト（404 にしない・取得失敗は再試行画面）、BE が独立して 403 を返す（FE 依存でない）
- [ ] SYSTEM_ADMIN が scope ロールを持たない場合、FE トグルを出さず BE も 403（FE/BE 整合）
- [ ] 退会済み=匿名表示・退会申請中=「手続き中」バッジで区別・操作不可（PII 復元なし）
- [ ] `isAdmin` 常時 true・独自 visibility 述語が無い
