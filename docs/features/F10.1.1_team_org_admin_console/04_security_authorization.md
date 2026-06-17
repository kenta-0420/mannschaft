# F10.1.1 / 04: 認可・セキュリティ設計

> **ステータス**: 🟢 設計確定
> **最終更新**: 2026-06-17
> **関連**: [docs/security/01_authorization_baseline.md](../../security/01_authorization_baseline.md) / [F00_content_visibility_resolver.md](../F00_content_visibility_resolver.md) / [README.md](./README.md)

---

## 1. 認可の基本方針

本機能は新しい認可基盤を作らない。既存の以下を流用する:

| 用途 | 流用する基盤 | 配置 |
|------|------------|------|
| ADMIN/DEPUTY 判定（強制） | `AccessControlService.checkAdminOrAbove(userId, scopeId, scopeType)` | `com.mannschaft.app.common` |
| ADMIN/DEPUTY 判定（真偽） | `AccessControlService.isAdminOrAbove(...)` | 同上 |
| 実効ロール解決（両系統統合） | `AccessControlService.resolveEffectiveRoleName(...)` | 同上 |
| 権限グループ判定（ORG） | `AccessControlService.checkAdminOrHasPermission(userId, scopeId, "ORGANIZATION", perm)` | 同上 |
| 可視性判定 | `ContentVisibilityResolver` / `StandardVisibility.ADMINS_AND_ABOVE` | `com.mannschaft.app.common.visibility` |
| テナント絞り込み | `AbstractTenantAwareRepository` | `com.mannschaft.app.common.repository` |
| FE 表示制御（最終判断ではない） | `useRoleAccess(scopeType, slug).isAdminOrDeputy` | `frontend/app/composables/useRoleAccess.ts` |

---

## 2. isAdmin 常時 true 禁止・サーバが最終判断

- **FE はあくまで表示制御**。`useRoleAccess` で取得したロールは「誤遷移の早期遮断」「ボタンの出し分け」にのみ使う。
- **すべての管理 API は BE で必ず `checkAdminOrAbove`（または権限グループ判定）を入口で通す**。本機能で新設する API（[03](./03_admin_action_required_api.md) の `admin-action-required`）はファサード入口で `checkAdminOrAbove` を呼ぶ。既存流用 API（予約・予算・メンバー）は各 Controller/Service が既に同等の認可を持つため、それを温存する。
- FE ミドルウェア（[01](./01_console_routes.md) §5）の 404 は UX 上の遮断であり、これだけに依存しない。仮に FE 判定を改ざんしても、BE が 403 を返すため情報は漏れない。
- `isAdmin` をハードコードで true にする・テストの便宜で常時許可する等は禁止（メモリ `feedback_visibility_bypass_f00_audit`）。

---

## 3. 可視性は F00 正準ラダー経由

- 管理者ウィジェット・管理コンソールの可視性レベルは **`StandardVisibility.ADMINS_AND_ABOVE`**（正準ラダーの5番目）を使う。独自の `visibility` 述語・独自ロールゲートを新設しない（メモリ `feedback_visibility_bypass_f00_audit`）。
- F02.2.1 のウィジェット可視性は従来 `min_role ∈ {PUBLIC, SUPPORTER, MEMBER}` の3値だった。本機能で **`min_role = ADMINS_AND_ABOVE` を管理者ウィジェット（`ADMIN_*` キー）専用に追加**する。F02.2.1 §6.1 を改訂し「管理者ウィジェットは `ADMINS_AND_ABOVE` 固定で、ADMIN/DEPUTY が F02.2 既存の表示ロール設定で個別に出し分ける（PUBLIC/SUPPORTER/MEMBER に下げることは不可）」と明記する。
- `viewerRole.isAtLeast(ADMINS_AND_ABOVE)` が false のウィジェットはサーバ側でレスポンスから省略する（データを送らない）。メンバーレンズ・非管理者に管理者情報が混入しない。

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

- **組織スコープ**: `accessControlService.checkAdminOrHasPermission(userId, orgId, "ORGANIZATION", "BUDGET_VIEW")`（ADMIN 無条件・DEPUTY は権限保有時）。これは既存メソッド。
- **チームスコープ**: `checkAdminOrHasPermission` は現状 ORGANIZATION 専用のため、チームでは `isAdmin(userId, teamId, "TEAM") || hasPermission(userId, teamId, "TEAM", "BUDGET_VIEW")` を明示的に組む。将来 `checkAdminOrHasPermission` をチーム対応に一般化する余地を残す（[05](./05_decisions.md) §4）。
- ADMIN 専用機能（ロール変更・モジュール ON/OFF）は `checkAdminOrAbove` ではなく `isAdmin`（DEPUTY を除外する真の ADMIN 判定）で守る。

### 4.3 新規パーミッション定数

本機能で参照する権限グループパーミッション:

| 定数 | 用途 | 既存/新規 |
|------|------|---------|
| `BUDGET_VIEW` | 予算サマリ・一覧の閲覧 | 既存（budget ドメインで定義済みなら流用、無ければ新設） |
| `BUDGET_MANAGE` | 予算の編集 | 同上 |
| `POINT_CARD_STAMP_ISSUE` | ポイント押印 | 既存 |

> パーミッション定数の実体（permissions マスタへの登録）は当該ドメインに属する。本機能はそれを参照するのみ。未定義の場合は budget ドメイン側で `is_default` の妥当な初期値（ADMIN=on, DEPUTY=off）とともに新設する。

---

## 5. IDOR・テナント越境の防止

- 管理 API はすべて `scope_type + scope_id` で絞り込む。`scope_id` はパスパラメータで受けるが、**認可は `SecurityUtils.getCurrentUserId()` の所属＋ロールで判定**する（リクエストボディの userId は信用しない）。
- 他組織/他チームの `scope_id` を与えても、`checkAdminOrAbove(currentUserId, otherScopeId, scopeType)` が当該ユーザーの非所属を検知して 403 を返す → 他テナントのデータは一切返らない。
- 集約 API（[03](./03_admin_action_required_api.md)）の各ドメイン集約も、件数 `COUNT` の WHERE 句に必ず `scope_id` を含める。`organization_id` 絞り込みは `AbstractTenantAwareRepository` のメソッド（`findByOrganizationIdAndDeletedAtIsNull` 等）を用い、テナント絞り込みを基底クラスに集約する（将来のシャーディングのため）。
- FE ミドルウェアは別組織 slug でのアクセスを 404 で先行遮断（[01](./01_console_routes.md) §5）。これは存在秘匿の UX 層。

---

## 6. 退会・匿名化（§13.12）と PII

- メンバー管理画面（[01](./01_console_routes.md) §3.4）は退会済みメンバーを表示しうる。退会済みユーザーは `user.anonymize()` で表示名・メール・アイコンが匿名化済みのため、**管理画面は匿名化後の値をそのまま表示する**。原 PII を復元・特別表示しない。
- 管理 API のレスポンスに含まれる `requested_by` 等の表示名は、`UserService.getDisplayNames` 経由で取得し、匿名化済みユーザーは匿名表示名を返す（個別ドメインがアクセスする際も同じ Service を通す）。
- 本機能は新規 PII テーブルを作らないため、退会フロー（即時消去/猶予の二段モデル）への新規追加対応は不要。L1 レンズの `adminLens` 状態は `localStorage` のみで DB 保存しない（PII でも DB データでもない）。

---

## 7. レートリミット・監査ログ

- 集約 API（GET・読み取り）は管理 API の既存レート（`/admin/**` 1分間60件相当）の枠で運用。読み取りのため厳格な個別制限は不要。
- 承認・却下の**実操作は各ドメインの既存 API が既存の監査ログ**（`audit_logs`）を記録する。集約 API は読み取りのため監査ログを発行しない。
- L1 管理者レンズのトグル切替は監査対象外（クライアント表示状態）。

---

## 8. セキュリティ・チェックリスト（検分用）

- [ ] 新設 `admin-action-required` API がファサード入口で `checkAdminOrAbove` を通す
- [ ] 各ドメインの `pendingForScope` が WHERE に `scope_id` を含む（テナント越境なし）
- [ ] 管理者ウィジェットの `min_role = ADMINS_AND_ABOVE`、非該当はレスポンスから省略
- [ ] DEPUTY 細粒度ゲート（BUDGET_VIEW 等）が ORG は `checkAdminOrHasPermission`、TEAM は明示判定
- [ ] FE ミドルウェアが非管理者に 404、ただし BE が独立して 403 を返す（FE 依存でない）
- [ ] 退会済みメンバーが匿名化後の表示で出る（PII 復元なし）
- [ ] `isAdmin` 常時 true・独自 visibility 述語が無い
