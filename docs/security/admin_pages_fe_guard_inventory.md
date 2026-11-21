# `pages/admin/` FE ガード棚卸し（CMP-261012-2343・部分完了）

> 対象: `frontend/app/pages/admin/` 配下の全 42 ページ。
> 状況: SYSTEM_ADMIN 専用23ページは是正済み。スコープ系の12ページは要判断で未是正のため、課題は完了していない。
> 前提: FE ガードは UX（誤遷移の早期遮断）であり認可の最終判断ではない。BE が各 API で必ず認可する。
> 番人: `frontend/tests/unit/middleware/admin-pages-guard.spec.ts`（全ページの分類漏れと、SYSTEM_ADMIN 専用ページの `system-admin` ミドルウェア欠落を検出する）。

## 1. 調査結果の要旨

- 全 42 ページが `middleware: 'auth'`（ログイン確認のみ）で、ロール判定は無かった。
- `/system-admin` 配下は CMP-260930-1933 で `['auth', 'system-admin']` に揃っている（前例）。
- 想定ロールは、各ページが呼ぶ API の BE 認可で決めた。
  - SYSTEM_ADMIN: `/api/v1/system-admin/**`（`SecurityConfig` の `hasRole("SYSTEM_ADMIN")`）、`/api/v1/admin/{reports,moderation,warning-re-reviews,users}/**`（同 hasRole）、`/api/v1/admin/audit-logs`（`AuditLogQueryService`）、`/api/v1/admin/point-cards/synonyms`（Service 層 `checkSystemAdmin`）。
  - スコープ ADMIN 系: `AdminFeedbackController` 等（`accessControlService.checkAdminOrAbove`）。

## 2. 是正済み（23 ページ: `['auth', 'system-admin']` を追加）

ad-credit-limit-requests / ad-rate-cards / advertiser-accounts / affiliate-settings / announcements / appeals / audit-logs / campaigns / feature-flags / maintenance / moderation / module-pricing / modules / organizations / packages / point-card-synonyms / reports / seasonal-wallpapers / storage-plans / tax-settings / templates / users / villages/creation-requests

（villages/creation-requests はページ内にも `isSystemAdmin` 判定があるが、ミドルウェアでも早期遮断する。）

## 3. スコープ ADMIN 系ページ（19 ページ）

入口ガード（権限が無ければ遷移を止める）の有無で分ける。`useShiftBudgetAdminAccess` は導線（ボタン等）の出し分けだけで遷移を止めないため、入口ガードには数えない。

### 3.1 入口ガードあり（7 ページ）

| ページ | 想定ロール | 現ガード |
|---|---|---|
| bulletin-categories / line-settings / receipt-settings / receipts / schedule-settings / sns-settings | スコープ ADMIN/DEPUTY_ADMIN | `middleware: 'auth'` + ページ内 `useAdminScopeGuard` |
| vendors/index | スコープ ADMIN 系 | `auth` + ページ内 `useAdminScopeGuard`（URL クエリのスコープ） |

### 3.2 入口ガードなし = 要判断（12 ページ・未是正）

| ページ | 想定ロール | 現ガード |
|---|---|---|
| dashboard / feedbacks / member-permissions / permission-groups | スコープ ADMIN 以上（BE は `checkAdminOrAbove` 等） | `auth` のみ |
| promotions / proxy-desk / proxy/consents / proxy/records | スコープ権限（BE 要件を要確認） | `auth` のみ |
| shift-budget/{alerts,allocations,dashboard,failed-events} | BUDGET_ADMIN（BE が Service 層で必須化） | `auth` + `useShiftBudgetAdminAccess`（導線の出し分けのみ）。一般会員が URL 直打ちすると画面は開く（データは BE が 403） |

番人 `admin-pages-guard.spec.ts` はこの12ページを明示リストで持つ。ガードを実装したらリストから 3.1 側へ移す（外れたガードは赤で検出される）。

## 4. 判断が割れる点（未実装）

1. 入口ガードが無い 12 ページ（`auth` のみの8ページ＋shift-budget 4ページ）は、`useAdminScopeGuard` を呼べば足りるが、個人スコープ（対象外）の扱い、`feedbacks` / `dashboard` が複数スコープを横断表示する設計か否か、`proxy-*`・`promotions` の BE 要件が未確認であり、実装前にマスターの方針が要る。
2. `useAdminScopeGuard` の `minRole` 既定（DEPUTY_ADMIN）で良いか、ページごとに ADMIN を要求するか。
3. BE が admin API を実際に守っているかは別課題（本件は FE のみ。`permission-groups` / `member-permissions` / `AdminDashboardController` は Controller に `@PreAuthorize` が無く Service 層の確認が必要）。
