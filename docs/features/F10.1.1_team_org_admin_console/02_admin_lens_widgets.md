# F10.1.1 / 02: L1 管理者レンズ・ウィジェット設計

> **ステータス**: 🟢 設計確定
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

- トグルは `useRoleAccess(scopeType, scopeId).isAdminOrDeputy` が true の場合のみ表示。非管理者には DOM ごと存在しない（FE 表示制御）。
- 既定は「メンバー」レンズ。選択状態は `useScopeDashboardStore`（F22.1 既存 Pinia ストア）に `adminLens: Record<scopeKey, boolean>` を追加し、`localStorage` 同期する（F22.1 のタグ順保持と同様）。
- レンズはスコープ（チームA / 組織X）単位で独立。チームAで管理者レンズにしても組織Xはメンバーレンズのまま。

### 1.3 コンポーネント

| コンポーネント | パス | 役割 |
|---------------|------|------|
| `DashboardAdminWidgetGrid.vue`（**新規**） | `frontend/app/components/dashboard/DashboardAdminWidgetGrid.vue` | 管理者向けウィジェットのグリッド。`DashboardSwipeWidgetGrid` と同じ props（`scopeType` / `scopeId` / `data`）を受ける |
| `DashboardScopeLensToggle.vue`（**新規**） | `frontend/app/components/dashboard/DashboardScopeLensToggle.vue` | メンバー/管理者の2値トグル。`isAdminOrDeputy` で表示制御 |
| `DashboardTeamPanel.vue` / `DashboardOrgPanel.vue`（**改修**） | 既存 | トグルとレンズ分岐（`v-if="adminLens"`）を追加 |

> `DashboardAdminWidgetGrid` を `DashboardSwipeWidgetGrid` と**別コンポーネントに切り出す**ことで、F22.1 のメンバー向けグリッドに管理者ロジックが混ざるのを防ぐ（README §1.1 原則3）。

---

## 2. L1 管理者ウィジェット一覧

L1 は**サマリ＋要対応バッジ＋専用ページへの導線のみ**の軽量グランス。深い操作・一覧表示は L2/L3（[01](./01_console_routes.md)）へ遷移する。データは原則として既存の `GET /api/v1/dashboard/{team|organization}/{id}` レスポンスの拡張フィールド＋承認待ち集約 API を使う。

### 2.1 チームパネル（管理者レンズ）

| # | widget_key | 名称 | min_role | データソース API | サマリ内容 | 導線先ルート |
|---|-----------|------|:--------:|----------------|-----------|------------|
| ① | `ADMIN_TEAM_RESERVATIONS` | 予約 | ADMINS_AND_ABOVE | `GET .../dashboard/team/{id}`（`adminReservationSummary` 新規フィールド）。詳細は `GET /teams/{id}/reservations?status=PENDING` | 承認待ち件数 / 本日の予約数 | `/teams/[slug]/admin/reservations` |
| ② | `ADMIN_TEAM_BUDGET` | 予算 | ADMINS_AND_ABOVE（BUDGET_VIEW 権限で DEPUTY 解放） | `GET .../budget/fiscal-years/{current}/summary` | 当年度 配分/実績/残・超過カテゴリ数 | `/teams/[slug]/admin/budget` |
| ③ | `ADMIN_TEAM_APPROVALS` | 承認待ち | ADMINS_AND_ABOVE | `GET .../dashboard/team/{id}/admin-action-required`（[03](./03_admin_action_required_api.md)） | ドメイン横断の `total_pending` ＋ドメイン別内訳 | `/teams/[slug]/admin/approvals` |
| ④ | `ADMIN_TEAM_MEMBERS` | メンバー統計 | ADMINS_AND_ABOVE | `GET .../dashboard?scope`（`member_stats`） | 総数 / アクティブ / 今月新規 / 入会申請件数 | `/teams/[slug]/admin/members` |
| ⑤ | `ADMIN_TEAM_ALERT` | 業務アラート | ADMINS_AND_ABOVE | `WidgetAdminBusinessAlert` 系サマリ（新規予約・未読問い合わせ） | 新規予約 / 未読問い合わせ件数 | `/teams/[slug]/admin/reservations`・問い合わせ画面 |
| ⑥ | `ADMIN_TEAM_REPORTS` | 通報 | ADMINS_AND_ABOVE | `GET .../dashboard`（`report_stats`・F10.1 母体） | 未対応 / 確認中 件数 | F10.1 母体のモデレーション画面 |
| ⑦ | `ADMIN_TEAM_MODULES` | モジュール | ADMINS_AND_ABOVE | `GET /teams/{id}/admin/modules` | 有効 N / 全 M | `/teams/[slug]/admin/settings/modules` |
| ⑧ | `ADMIN_TEAM_CONSOLE` | 管理コンソール | ADMINS_AND_ABOVE | （導線のみ） | 「管理コンソールを開く」 | `/teams/[slug]/admin`（L2 ハブ） |

### 2.2 組織パネル（管理者レンズ）

| # | widget_key | 名称 | min_role | データソース API | サマリ内容 | 導線先ルート |
|---|-----------|------|:--------:|----------------|-----------|------------|
| ① | `ADMIN_ORG_RESERVATIONS` | 予約 | ADMINS_AND_ABOVE | `GET .../dashboard/organization/{id}`（`adminReservationSummary`）/ `GET /organizations/{id}/reservations?status=PENDING` | 承認待ち / 本日の予約 | `/organizations/[slug]/admin/reservations` |
| ② | `ADMIN_ORG_BUDGET` | 予算 | ADMINS_AND_ABOVE（BUDGET_VIEW で DEPUTY 解放） | `GET .../budget/fiscal-years/{current}/summary`（組織） | 配分/実績/残・超過カテゴリ数 | `/organizations/[slug]/admin/budget` |
| ③ | `ADMIN_ORG_APPROVALS` | 承認待ち | ADMINS_AND_ABOVE | `GET .../dashboard/organization/{id}/admin-action-required` | 横断 `total_pending` ＋内訳 | `/organizations/[slug]/admin/approvals` |
| ④ | `ADMIN_ORG_MEMBERS` | メンバー統計 | ADMINS_AND_ABOVE | `GET .../dashboard?scope`（`member_stats`） | 総数 / アクティブ / 今月新規 / 入会申請件数 | `/organizations/[slug]/admin/members` |
| ⑤ | `ADMIN_ORG_ALERT` | 業務アラート | ADMINS_AND_ABOVE | `WidgetAdminBusinessAlert` 系サマリ | 新規予約 / 未読問い合わせ | `/organizations/[slug]/admin/reservations` |
| ⑥ | `ADMIN_ORG_REPORTS` | 通報 | ADMINS_AND_ABOVE | `GET .../dashboard`（`report_stats`） | 未対応 / 確認中 | F10.1 母体モデレーション |
| ⑦ | `ADMIN_ORG_POINTCARDS` | ポイントカード | ADMINS_AND_ABOVE（POINT_CARD_STAMP_ISSUE で DEPUTY 解放） | F18 既存サマリ | 稼働プロバイダー数 | `/organizations/[slug]/admin/point-cards` |
| ⑧ | `ADMIN_ORG_CONSOLE` | 管理コンソール | ADMINS_AND_ABOVE | （導線のみ） | 「管理コンソールを開く」 | `/organizations/[slug]/admin`（L2 ハブ） |

> **命名方針**: `ADMIN_` プレフィックスで F22.1 の `SWIPE_` メンバー向けウィジェットと名前空間を分離する。`dashboard_widget_settings.widget_key` 列にそのまま格納でき、テーブル変更は不要。

---

## 3. 管理者向けアラート（F10.7 WidgetAdminBusinessAlert との関係）

- 既存 `WidgetAdminBusinessAlert.vue` は**個人ダッシュボード**で全所属チームの予約・問い合わせアラートを横断表示する別ウィジェット。L1 管理者レンズの ⑤ アラートウィジェットは**特定スコープ単位**で同種のサマリ（新規予約・承認待ち・未読問い合わせ）を表示する。
- データソースは既存の `WidgetAdminBusinessAlert` が呼ぶサマリ API をスコープ絞り込みで再利用する（個人横断版とスコープ単位版でレスポンス DTO を共有）。承認待ち件数は承認待ち集約 API（[03](./03_admin_action_required_api.md)）の `total_pending` を使い、二重集計しない。
- 個人レンズの `WidgetAdminBusinessAlert`（横断）と L1 管理者レンズ ⑤（スコープ単位）は**併存**する。前者は「全所属を一望」、後者は「いま見ているチーム/組織に集中」という役割分担。

---

## 4. 可視性ゲート（F02.2.1 連携）

### 4.1 管理者ウィジェットの min_role

- 全管理者ウィジェットの `min_role` は **`ADMINS_AND_ABOVE`**（StandardVisibility 正準ラダー §5.1.5）とする。F02.2.1 の `min_role` 列が取りうる値は従来 `PUBLIC / SUPPORTER / MEMBER` の3値だったが、本機能で **`ADMINS_AND_ABOVE` を管理者ウィジェット専用に追加**する（F02.2.1 §6.1 改訂。詳細は [04](./04_security_authorization.md) §3）。
- サーバ側で `viewerRole.isAtLeast(ADMINS_AND_ABOVE)` が false のウィジェットは `data` から省略する（F22.1 §1.4 と同方式・既存 `DashboardService` 踏襲）。管理者限定情報がメンバーレンズや非管理者に漏れることはない。

### 4.2 DEPUTY_ADMIN の細粒度ゲート

`ADMINS_AND_ABOVE` は ADMIN と DEPUTY_ADMIN の両方を含むが、課金・予算・ポイントカードのように DEPUTY に常時見せたくない情報は、**`min_role` に加えて権限グループパーミッションで二段ゲート**する:

| ウィジェット | 追加ゲート（DEPUTY 解放条件） |
|-------------|---------------------------|
| `ADMIN_*_BUDGET` | `BUDGET_VIEW` パーミッション（権限グループ経由）|
| `ADMIN_ORG_POINTCARDS` | `POINT_CARD_STAMP_ISSUE` パーミッション（既存）|

判定は BE 側で `accessControlService.checkAdminOrHasPermission(userId, scopeId, "ORGANIZATION", "BUDGET_VIEW")` を用いる（ADMIN は無条件、DEPUTY は権限保有時のみ）。FE では当該ウィジェットの `data` 省略で非表示になる。

> **注**: `checkAdminOrHasPermission` は現状 ORGANIZATION 専用。チームスコープの予算ウィジェットの DEPUTY ゲートは、`isAdmin` ∨ `hasPermission(... "TEAM" ... "BUDGET_VIEW")` の明示判定で代替する（[04](./04_security_authorization.md) §4.2）。

---

## 5. L1 → L2/L3 の責務分離

| 層 | 責務 | データ取得 |
|----|------|-----------|
| L1（レンズ） | 件数・サマリの一目把握、要対応バッジ、導線 | ダッシュボード API の拡張フィールド + 承認待ち集約 API（軽量・キャッシュ可） |
| L2（ハブ） | カテゴリ選択、各セクションへの分岐、バッジ | 承認待ち集約 API のサマリ |
| L3（セクション） | 一覧・詳細・実操作（CRUD・承認実行） | 各ドメインの既存 CRUD/承認 API |

L1 のウィジェットは**読み取り専用**。承認・キャンセル等の実操作ボタンは L1 に置かず、必ず L3 へ遷移してから実行する（誤操作防止＋L1 の軽量性維持）。
