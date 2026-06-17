# F10.1.1 / 02: L1 管理者レンズ・ウィジェット設計

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-06-17
> **関連**: [README.md](./README.md) / [F22.1_swipe_scope_dashboard/04_widgets.md](../F22.1_swipe_scope_dashboard/04_widgets.md) / [F02.2.1_dashboard_widget_role_visibility.md](../F02.2.1_dashboard_widget_role_visibility.md)

本書は L1「管理者レンズ」の UI 切替方式と、管理者向けウィジェット群（`DashboardAdminWidgetGrid`）の一覧を定義する。

---

## 1. レンズ切替の方式

### 1.1 軸を混ぜない（第4タブを作らない）

横スワイプの軸は F22.1 で確定した「スコープ（個人/チーム/組織）」のまま温存する。管理者ビューは**第4タブ『管理』ではなく、チーム/組織パネル内のレンズ切替トグル**で表現する。理由は [05_decisions.md](./05_decisions.md) §1。

```
DashboardTeamPanel / DashboardOrgPanel のタグ行右端にトグルを置く:

  #チームA  #チームB  #チームC   ‹1/2›    [ 👤 メンバー | 🛡 管理者 ]
                                              └─ レンズトグル（ADMIN/DEPUTY のみ表示）

  トグル = メンバー（既定） → <DashboardSwipeWidgetGrid>（F22.1 既存・厳選8）
  トグル = 管理者         → <DashboardAdminWidgetGrid>（本書・管理8）
```

### 1.2 トグルの表示条件と状態保持

- トグルは `useRoleAccess(scopeType, slug).isAdminOrDeputy` が true の場合のみ表示。非管理者には DOM ごと存在しない（FE 表示制御）。**第2引数は slug 文字列**（`useRoleAccess(scopeType: 'team'|'organization', scopeId: Ref<string>|string)`・R8 偵察で確定）。先行版の「scopeId」表記を slug に統一した。`isAdminOrDeputy` は SYSTEM_ADMIN でも true（R8）。
- 既定は「メンバー」レンズ。選択状態は `useScopeDashboardStore`（F22.1 既存 Pinia ストア）に新フィールド `adminLens: Record<string, boolean>` を追加して保持し、`localStorage` 同期する（F22.1 のタグ順保持と同様）。
- レンズはスコープ（チームA / 組織X）単位で独立。チームAで管理者レンズにしても組織Xはメンバーレンズのまま。

#### scopeKey の生成規則と store フィールド型【宙ぶらりん解消】

`adminLens` のキー `scopeKey` は **`${scopeType}:${slug}`**（例 `TEAM:dev-team` / `ORGANIZATION:acme`）で一意化する。slug はスコープ内で一意かつ URL 識別子の正準（メモリ `project_url_identifier_slug_canonical`）であり、数値 ID を持ち出さない。

```typescript
// useScopeDashboardStore に追加するフィールド（F22.1 既存ストアを拡張）
interface ScopeDashboardState {
  // …F22.1 既存フィールド（activePanel / tabOrders 等）…
  adminLens: Record<string, boolean>   // key = `${ScopeTabType}:${slug}`, value = 管理者レンズON
}

// アクション
function scopeKey(scopeType: ScopeTabType, slug: string): string {
  return `${scopeType}:${slug}`
}
function setAdminLens(scopeType: ScopeTabType, slug: string, on: boolean): void {
  adminLens.value[scopeKey(scopeType, slug)] = on
  persistToStorage()   // F22.1 既存の localStorage 'scope-dashboard' キーに同梱保存
}
function isAdminLensOn(scopeType: ScopeTabType, slug: string): boolean {
  return adminLens.value[scopeKey(scopeType, slug)] ?? false   // 既定 false（メンバーレンズ）
}
```

`adminLens` は F22.1 既存の `localStorage` キー `scope-dashboard` の JSON に同梱して永続化する（新規 localStorage キーは作らない）。PII でも DB データでもないため DB 保存しない（[04](./04_security_authorization.md) §6）。

### 1.3 トグルとモバイル横スワイプの操作競合【改善提案・操作競合分析】

L1 トグルはタップ操作、F22.1 カルーセルは横スワイプ操作で、ジェスチャが競合しうる。F22.1 §3.3 の方針（スワイプ閾値＝横移動量 > 画面幅の 20% かつ `|Δx| > |Δy| * 1.5`）に整合させ、以下で競合を回避する:

- トグルは**タグ行の右端**（カルーセルのスワイプ領域内）に置くが、トグル自体は `role="switch"` のボタンであり、`click`/`tap`（`touchend` で移動量が閾値未満）でのみ反応する。横方向に閾値以上ドラッグした場合はカルーセルのパネル送りが優先され、トグルは発火しない（タップとスワイプを移動量で排他判定）。
- トグルのタップ領域は最小 44×44px（モバイルの誤タップ防止）。スワイプ開始点がトグル上でも、`touchmove` の横移動量が閾値を超えたらカルーセルへジェスチャを委譲する（`touchstart` 時点でトグルを「押下中」にせず、`touchend` で移動量を確定してから発火）。

### 1.4 コンポーネント

| コンポーネント | パス | 役割 |
|---------------|------|------|
| `DashboardAdminWidgetGrid.vue`（**新規**） | `frontend/app/components/dashboard/DashboardAdminWidgetGrid.vue` | 管理者向けウィジェットのグリッド。`DashboardSwipeWidgetGrid` と同じ props（`scopeType` / `slug` / `data`）を受ける |
| `DashboardScopeLensToggle.vue`（**新規**） | `frontend/app/components/dashboard/DashboardScopeLensToggle.vue` | メンバー/管理者の2値トグル（`role="switch"`）。`isAdminOrDeputy` で表示制御。§1.3 のジェスチャ排他 |
| `DashboardTeamPanel.vue` / `DashboardOrgPanel.vue`（**改修**） | 既存 | トグルとレンズ分岐（`v-if="isAdminLensOn(...)"`）を追加。メンバー視点へ戻る導線（§1.5） |

> `DashboardAdminWidgetGrid` を `DashboardSwipeWidgetGrid` と**別コンポーネントに切り出す**ことで、F22.1 のメンバー向けグリッドに管理者ロジックが混ざるのを防ぐ（README §1.1 原則3）。

### 1.5 レンズ往復導線・発見性（L1↔L2/L3）【改善提案】

- **トグルの発見性**: 管理者レンズトグルは ADMIN/DEPUTY 初回表示時に短いツールチップ（「管理者ビューに切り替え」）を1回出す（`localStorage` で既読管理）。アイコン（🛡）＋ラベルでメンバー/管理者の2状態を明示。
- **メンバー視点へ戻る導線**: 管理者レンズ中も、トグルは常時表示され、ワンタップで「メンバー」へ戻れる。管理者ウィジェットの先頭に「メンバービューに戻る」のサブリンクも置く。
- **L1↔L2 往復**: 管理者レンズの ⑧「管理コンソールを開く」で L2 ハブ（`/teams|organizations/[slug]/admin`）へ遷移。L2 ハブには「ダッシュボードに戻る」リンクを置き、L1（`/dashboard` のチーム/組織パネル・管理者レンズ）へ戻れる。L2→L1 戻り時はレンズ状態（`adminLens`）が保持されているため、管理者レンズのまま着地する。

---

## 2. L1 管理者ウィジェット一覧

L1 は**サマリ＋要対応バッジ＋専用ページへの導線のみ**の軽量グランス。深い操作・一覧表示は L2/L3（[01](./01_console_routes.md)）へ遷移する。データは原則として既存の `GET /api/v1/dashboard/{team|organization}/{id}` レスポンスの拡張フィールド＋承認待ち集約 API を使う。

### 2.1 管理者ウィジェットの可視性ラダーは min_role ではなく ADMINS_AND_ABOVE 固定【C・min_role 衝突 根治】

> **偵察結果（R6/R7）に基づく重大訂正**: backend の `MinRole` enum は **`PUBLIC` / `SUPPORTER` / `MEMBER` の3値のみ**であり、`ADMINS_AND_ABOVE` は `MinRole` ではなく **`StandardVisibility` enum の値**である。`dashboard_widget_role_visibility.min_role` 列と Service 層検証（`MinRole.fromString`）は3値前提で、ここに `ADMINS_AND_ABOVE` を追加すると検証が壊れる。先行版の「min_role に ADMINS_AND_ABOVE を追加する」は**実装と矛盾するため撤回**する。
>
> 正しい扱い: **管理者ウィジェット（`ADMIN_*` キー）は F02.2.1 の widget-visibility テーブルの管理対象外**とし、既存の ADMIN 限定ウィジェット（`TEAM_BILLING` / `ORG_BILLING` / `TEAM_PAGE_VIEWS`。`WidgetKey.ROLE_RESTRICTED` 集合・`WidgetDefaultMinRoleMap.isConfigurable()=false`）と**同じ扱いに揃える**。すなわち管理者ウィジェットは「DB の min_role で出し分けない・UI 設定対象外・コードで可視性ラダー `ADMINS_AND_ABOVE` 固定」とする。

各ウィジェットの可視性は、`min_role`（3値）ではなく **`StandardVisibility.ADMINS_AND_ABOVE`（ADMIN+DEPUTY を包含・R7 確定）** を**コードで固定**してゲートする。表中の「可視性」列は `ADMINS_AND_ABOVE` 固定（DB 設定不可）を意味する。

### 2.2 チームパネル（管理者レンズ）

| # | widget_key | 名称 | 可視性（コード固定） | データソース API | サマリ内容 | 導線先ルート |
|---|-----------|------|:------:|----------------|-----------|------------|
| ① | `ADMIN_TEAM_RESERVATIONS` | 予約 | ADMINS_AND_ABOVE | `GET .../dashboard/team/{id}`（`adminReservationSummary` 新規フィールド）。詳細は `GET /teams/{id}/reservations?status=PENDING` | 承認待ち件数 / 本日の予約数 | `/teams/[slug]/admin/reservations` |
| ② | `ADMIN_TEAM_BUDGET` | 予算 | ADMINS_AND_ABOVE（`BUDGET_VIEW` 権限で DEPUTY 解放・§4.2） | `GET .../budget/fiscal-years/{current}/summary` | 当年度 配分/実績/残・超過カテゴリ数 | `/teams/[slug]/admin/budget` |
| ③ | `ADMIN_TEAM_APPROVALS` | 承認待ち | ADMINS_AND_ABOVE | `GET .../dashboard/team/{id}/admin-action-required`（[03](./03_admin_action_required_api.md)・team は予約/シフト/マッチング） | ドメイン横断の `total_pending` ＋ドメイン別内訳 | `/teams/[slug]/admin/approvals` |
| ④ | `ADMIN_TEAM_MEMBERS` | メンバー統計 | ADMINS_AND_ABOVE | `GET .../dashboard?scope`（`member_stats`） | 総数 / アクティブ / 今月新規 | `/teams/[slug]/admin/members` |
| ⑤ | `ADMIN_TEAM_ALERT` | 業務アラート | ADMINS_AND_ABOVE | `WidgetAdminBusinessAlert` 系サマリ（新規予約・未読問い合わせ・§3） | 新規予約 / 未読問い合わせ件数 | `/teams/[slug]/admin/reservations`・問い合わせ画面 |
| ⑥ | `ADMIN_TEAM_REPORTS` | 通報 | ADMINS_AND_ABOVE | `GET .../dashboard`（`report_stats`・F10.1 母体） | 未対応 / 確認中 件数 | F10.1 母体のモデレーション画面 |
| ⑦ | `ADMIN_TEAM_MODULES` | モジュール | ADMINS_AND_ABOVE | `GET /teams/{id}/admin/modules` | 有効 N / 全 M | `/teams/[slug]/admin/settings/modules` |
| ⑧ | `ADMIN_TEAM_CONSOLE` | 管理コンソール | ADMINS_AND_ABOVE | （導線のみ） | 「管理コンソールを開く」 | `/teams/[slug]/admin`（L2 ハブ） |

### 2.3 組織パネル（管理者レンズ）

| # | widget_key | 名称 | 可視性（コード固定） | データソース API | サマリ内容 | 導線先ルート |
|---|-----------|------|:------:|----------------|-----------|------------|
| ① | `ADMIN_ORG_APPROVALS` | 承認待ち（未収請求） | ADMINS_AND_ABOVE | `GET .../dashboard/organization/{id}/admin-action-required`（org は PAYMENT のみ・[03](./03_admin_action_required_api.md) §3.2） | `total_pending`（未収請求件数） | `/organizations/[slug]/admin/approvals` |
| ② | `ADMIN_ORG_BUDGET` | 予算 | ADMINS_AND_ABOVE（`BUDGET_VIEW` で DEPUTY 解放） | `GET .../budget/fiscal-years/{current}/summary`（組織） | 配分/実績/残・超過カテゴリ数 | `/organizations/[slug]/admin/budget` |
| ③ | `ADMIN_ORG_PAYMENTS` | 支払 | ADMINS_AND_ABOVE | `PaymentRequestService.findForOrg`（未完了請求） | 未収 / 期限超過 件数 | `/organizations/[slug]/admin/payments` |
| ④ | `ADMIN_ORG_MEMBERS` | メンバー統計 | ADMINS_AND_ABOVE | `GET .../dashboard?scope`（`member_stats`） | 総数 / アクティブ / 今月新規 | `/organizations/[slug]/admin/members` |
| ⑤ | `ADMIN_ORG_ALERT` | 業務アラート | ADMINS_AND_ABOVE | `WidgetAdminBusinessAlert` 系サマリ（未読問い合わせ・§3） | 未読問い合わせ件数 | 問い合わせ画面 |
| ⑥ | `ADMIN_ORG_REPORTS` | 通報 | ADMINS_AND_ABOVE | `GET .../dashboard`（`report_stats`） | 未対応 / 確認中 | F10.1 母体モデレーション |
| ⑦ | `ADMIN_ORG_POINTCARDS` | ポイントカード | ADMINS_AND_ABOVE（`POINT_CARD_STAMP_ISSUE` で DEPUTY 解放） | F18 既存サマリ | 稼働プロバイダー数 | `/organizations/[slug]/admin/point-cards` |
| ⑧ | `ADMIN_ORG_CONSOLE` | 管理コンソール | ADMINS_AND_ABOVE | （導線のみ） | 「管理コンソールを開く」 | `/organizations/[slug]/admin`（L2 ハブ） |

> **組織に予約ウィジェットを置かない理由**: 組織スコープの予約 API は存在しない（`ReservationEntity` に `organization_id` 無し・[03](./03_admin_action_required_api.md) §3.2）。先行版の `ADMIN_ORG_RESERVATIONS` は実体が無いため削除し、組織固有の `ADMIN_ORG_PAYMENTS`（支払・未収請求）に差し替えた。これにより各ウィジェットのデータソースが実在 API に対応する。

> **命名方針**: `ADMIN_` プレフィックスで F22.1 の `SWIPE_` メンバー向けウィジェットと名前空間を分離する。`dashboard_widget_settings.widget_key` 列（並び順・表示/非表示）にはそのまま格納できるが、**ロール別可視性（`dashboard_widget_role_visibility`）の管理対象には含めない**（§2.1・コード固定 `ADMINS_AND_ABOVE`）。テーブル変更は不要。

### 2.4 WidgetKey/WidgetKeyMap への ADMIN_* 追加手順【改善提案・F02.2.1 §12 手順準拠】

`ADMIN_*` ウィジェットを既存のウィジェット基盤に載せるため、F02.2.1 §12「新ウィジェット追加時の手順」と §3「widget_key 命名規則と対応マップ」に沿って以下を行う:

1. backend `WidgetKey.java` enum に `ADMIN_TEAM_*` / `ADMIN_ORG_*`（UPPER_SNAKE_CASE）を追加。同時に `ROLE_RESTRICTED` 相当の扱い（=`WidgetDefaultMinRoleMap.isConfigurable()` が false を返す＝min_role 管理対象外）に分類する。`TEAM_BILLING` 等と同じ「ADMIN 限定・UI 設定対象外」グループに入れる。
2. frontend の `WidgetKeyMap`（kebab-case ↔ UPPER_SNAKE_CASE）に `admin-team-reservations` ↔ `ADMIN_TEAM_RESERVATIONS` 等を追加（kebab は `admin-` プレフィックス）。
3. `WidgetDefaultMinRoleMap` には **登録しない**（min_role 管理対象外のため。`isConfigurable()` が false を返すことで widget-visibility 設定 UI / PUT から自動的に除外される）。可視性は §2.1 のとおりコードで `ADMINS_AND_ABOVE` 固定。
4. F02.2.1 §12 の CI 整合性テスト（`WidgetVisibilityConsistencyTest`）は「`isConfigurable()=true` のウィジェットのみ default min_role マップと突合」する契約のため、ADMIN_* は `isConfigurable()=false` で対象外となり CI を壊さない。ADMIN_* 専用に「`WidgetKey` enum に存在し、かつ `isConfigurable()=false`、かつ frontend `WidgetKeyMap` に対応がある」ことを検証する追加テストを登録する。

---

## 3. 管理者向けアラート（⑤）と未読問い合わせの実体特定【改善提案・二重計上回避】

- ⑤「業務アラート」のサマリは既存 `WidgetAdminBusinessAlert.vue`（個人ダッシュボードで全所属チームの予約・問い合わせを横断表示する別ウィジェット）が呼ぶサマリ API を、**スコープ絞り込み**で再利用する。
- **未読問い合わせの実体**: 問い合わせ（inquiry/contact）は `WidgetAdminBusinessAlert` が参照する既存サマリ（新規予約件数・未読問い合わせ件数）に含まれる。本機能で新たな問い合わせドメインを作らず、既存サマリのスコープ単位値を表示する。問い合わせの一覧 EP は `WidgetAdminBusinessAlert` の既存導線先（問い合わせ画面）をそのまま使う。
- **承認待ち（③）との二重計上回避**: ⑤アラートの「新規予約」と ③承認待ちの `RESERVATION.pending_count` は**同じ予約の異なる断面**になりうる。二重計上を避けるため、**承認待ち件数は ⑤ では表示せず ③ に一本化**する。⑤アラートは「新規予約（直近 N 時間に入った予約の通知的件数）」と「未読問い合わせ」のみを表示し、`total_pending`（承認待ち）は ③ の集約 API（[03](./03_admin_action_required_api.md) `total_pending`）だけが持つ。FE は ⑤ と ③ で同じ件数を二重に足し込まない。
- 個人レンズの `WidgetAdminBusinessAlert`（横断）と L1 管理者レンズ ⑤（スコープ単位）は**併存**する。前者は「全所属を一望」、後者は「いま見ているチーム/組織に集中」という役割分担。

---

## 4. 可視性ゲート（F02.2.1 連携）

### 4.1 管理者ウィジェットの可視性判定は集合判定 isAdminOrAbove を使う【C・DEPUTY 可視閾値 根治】

- 管理者ウィジェットの可視性は §2.1 のとおり `StandardVisibility.ADMINS_AND_ABOVE`（ADMIN+DEPUTY 包含）固定。
- **サーバ側の判定は集合判定 `accessControlService.isAdminOrAbove(userId, scopeId, scopeType)`（DEPUTY を含む・R7 確定）を使う**。閾値判定 `viewerRole.isAtLeast("ADMIN")` を使うと、ロール優先度の実装次第で DEPUTY_ADMIN が弾かれうる懸念があるため避ける。`isAdminOrAbove` は `ADMIN_ROLES = {"ADMIN","DEPUTY_ADMIN"}` の集合包含で判定し、DEPUTY を確実に含める。
- `isAdminOrAbove` が false のウィジェットはサーバ側で `data` から省略する（F22.1 §1.4 と同方式・既存 `DashboardService` 踏襲）。管理者限定情報がメンバーレンズや非管理者に漏れることはない。

> **実装注（DEPUTY の取りこぼし防止）**: `AccessControlService.isAdminOrAbove` は `getRoleName`（user_roles + memberships 統合の `resolveEffectiveRoleName` 系）の結果を `ADMIN_ROLES` 集合で判定する。memberships 専属の DEPUTY も両系統統合で正しく ADMIN/DEPUTY と解決される（メモリ `feedback_role_resolution_memberships_gap`）。閾値方式（priority 比較）に切り替えると統合解決と齟齬が出る危険があるため、必ず `isAdminOrAbove`（集合判定）を用いる。

### 4.2 DEPUTY_ADMIN の細粒度ゲート

`ADMINS_AND_ABOVE` は ADMIN と DEPUTY_ADMIN の両方を含むが、課金・予算・ポイントカードのように DEPUTY に常時見せたくない情報は、可視性ラダーに加えて権限グループパーミッションで二段ゲートする:

| ウィジェット | 追加ゲート（DEPUTY 解放条件） |
|-------------|---------------------------|
| `ADMIN_*_BUDGET` | `BUDGET_VIEW` パーミッション（権限グループ経由・実在 V11.034・scope=ORGANIZATION）|
| `ADMIN_ORG_POINTCARDS` | `POINT_CARD_STAMP_ISSUE` パーミッション（既存）|

判定:
- **組織スコープ**: `accessControlService.checkAdminOrHasPermission(userId, orgId, "ORGANIZATION", "BUDGET_VIEW")`（ADMIN は無条件、DEPUTY は権限保有時のみ）。`BUDGET_VIEW` は scope=ORGANIZATION で実在（R5）。
- **チームスコープ**: `checkAdminOrHasPermission` は実コードで **ORGANIZATION 専用**（TEAM を渡すと `IllegalArgumentException`・R7 確定）。チームの予算ウィジェットの DEPUTY ゲートは `isAdmin(userId, teamId, "TEAM")` ∨ `hasPermission(userId, teamId, "TEAM", "BUDGET_VIEW")` の明示判定で代替する（[04](./04_security_authorization.md) §4.2）。FE では当該ウィジェットの `data` 省略で非表示になる。

> `BUDGET_VIEW` は現状 scope=ORGANIZATION のみで seed されている（R5）。チームスコープで `hasPermission(... "TEAM" ... "BUDGET_VIEW")` を使うには、チームスコープの `BUDGET_VIEW` 権限割当が必要になる。この扱いは [04](./04_security_authorization.md) §4.3 に確定方針を記す。

---

## 5. L1 → L2/L3 の責務分離

| 層 | 責務 | データ取得 |
|----|------|-----------|
| L1（レンズ） | 件数・サマリの一目把握、要対応バッジ、導線 | ダッシュボード API の拡張フィールド + 承認待ち集約 API（軽量・キャッシュ可） |
| L2（ハブ） | カテゴリ選択、各セクションへの分岐、バッジ | 承認待ち集約 API のサマリ |
| L3（セクション） | 一覧・詳細・実操作（CRUD・承認実行） | 各ドメインの既存 CRUD/承認 API |

L1 のウィジェットは**読み取り専用**。承認・キャンセル等の実操作ボタンは L1 に置かず、必ず L3 へ遷移してから実行する（誤操作防止＋L1 の軽量性維持）。

---

## 6. FE 受信型（DTO 命名・型）【DTO 命名・FE 型 根治】

API レスポンスは snake_case（[03](./03_admin_action_required_api.md) §3.5）。FE は受信後に camelCase へ変換して以下の型で消費する。F22.1 のメンバー向け `ActionRequiredSummary`（[F22.1/03 §2.1](../F22.1_swipe_scope_dashboard/03_security_ux.md)）との**命名衝突を避けるため `AdminActionRequiredSummary`** とする。

```typescript
// frontend/app/types/admin-action-required.ts（新規）
export type AdminActionDomain = 'RESERVATION' | 'SHIFT_REQUEST' | 'MATCHING' | 'PAYMENT'

export interface AdminActionItem {
  id: string                 // BE は主キーを文字列化して返す
  title: string
  requestedBy: string        // requested_by → camelCase
  requestedAt: string        // ISO8601（requested_at）
  detailRoute: string        // detail_route（BE がスラッグ解決済み）
}

export interface AdminActionDomainSummary {
  domain: AdminActionDomain
  pendingCount: number       // pending_count
  degraded: boolean          // 集計失敗フラグ（0件と区別・[03] §4.3）
  listRoute: string          // list_route
  items: AdminActionItem[]
}

export interface AdminActionRequiredSummary {
  scopeType: 'TEAM' | 'ORGANIZATION'   // scope_type
  scopeId: number                       // scope_id
  totalPending: number                  // total_pending（degraded ドメインは加算されない）
  domains: AdminActionDomainSummary[]   // スコープ別に有効なドメインのみ（[03] §3.2）
}
```

> 変換規約: API=snake_case、FE 受信型=camelCase。`useDashboardApi` 系の取得関数は `unknown` を返さず `AdminActionRequiredSummary` を返すよう型付ける（`any` 禁止・F22.1 §2.2 と同方針）。
