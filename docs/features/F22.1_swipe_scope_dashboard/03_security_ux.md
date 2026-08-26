# F22.1: セキュリティ・UX・フロントエンド設計

## 容量サマリー

カルーセルの切替タブ直下に、個人・選択中チーム・選択中組織の容量を共通表示する。`GET /api/v1/me/storage/usage` はマウント時に一度だけ取得し、選択slugの変更に追随する。使用率80%以上を注意、90%以上を警告とし、容量枠未設定・未所属・取得失敗（カード内再試行）を明示する。モバイルは縦3行、md以上は3列とし、詳細は `/settings/storage` へ遷移する。容量カードは44px以上のキーボード操作可能なボタンとし、通常カードは `/settings/storage` へ遷移する。90%以上のカードは警告Dialogを開き、「プランを見る」「ストレージを確認」「キャンセル」を提示する。

> **ステータス**: 🟢 設計確定
> **最終更新**: 2026-05-30
> **関連ドキュメント**:
> - [README.md](./README.md) — 機能概要
> - [01_db_design.md](./01_db_design.md) / [02_api_design.md](./02_api_design.md) / [04_widgets.md](./04_widgets.md)
> - [F20.1_nav_customization/03_security_ux.md](../F20.1_nav_customization/03_security_ux.md) — store + plugin の手本
> - [docs/security/README.md](../../security/README.md) — 認可横断方針

---

## 1. セキュリティ設計

### 1.1 認可マトリクス

| 操作 | 認証済みユーザー | 非所属スコープに対して | 他人の設定に対して |
|------|:-----------:|:------------------:|:--------------:|
| GET /dashboard/scope-tabs（自分の所属タグ）| ✅ | 自動除外（返さない）| 不可（自分の userId 固定）|
| PUT /dashboard/scope-tabs/order（自分のタグ順）| ✅ | 403 SCOPE_TAB_001 | 不可（自分の userId 固定）|
| GET /dashboard/team/{id}（パネルデータ）| 所属時のみ ✅ | `checkMembership` で 403 | — |
| GET /dashboard/organization/{id} | 所属時のみ ✅ | `checkMembership` で 403 | — |
| GET /dashboard/{scope}/{id}/action-required | 所属時のみ ✅ + 各ドメイン認可 | 403 + 区分別認可 | — |
| GET /dashboard/scope-tabs?folderId=（フォルダフィルタ）| 自分所有フォルダのみ ✅ | — | 他人フォルダ 404 SCOPE_TAB_004 |

### 1.2 IDOR 防止

- すべての API は `SecurityUtils.getCurrentUserId()` を使用し、パスパラメータ・ボディに `userId` を**露出しない**。
- `PUT /scope-tabs/order`: `orders[].scopeId` 全件を `AccessControlService.checkMembership(userId, scopeId, scopeType)` で検証。1 件でも非所属なら**全体を 403**（部分適用しない）。
- `folderId`: `my_scope_folders.user_id = currentUserId` を検証してから `my_scope_folder_items` を参照。他人フォルダは 404（存在隠蔽）。
- `GET /dashboard/team/{id}` 等は既存の `accessControlService.checkMembership` をそのまま通す（変更なし）。

```java
// DashboardScopeTabService.updateOrder（擬似コード）
Long userId = SecurityUtils.getCurrentUserId();
for (OrderItem o : req.getOrders()) {
    if (!accessControlService.isMember(userId, o.getScopeId(), scopeType)) {
        throw new BusinessException(SCOPE_TAB_001); // 非所属混入 → 全体拒否
    }
}
repo.upsertOrders(userId, scopeType, req.getOrders());
```

### 1.3 入力バリデーション

| 入力 | ルール | エラーコード |
|------|-------|------------|
| `scopeType` | `TEAM` / `ORGANIZATION` のみ（enum）| SCOPE_TAB_003 |
| `orders` 要素数 | 1〜200 | COMMON_001 |
| `orders[].scopeId` | 自分の所属スコープのみ | SCOPE_TAB_001 |
| `orders[].sortOrder` | 0〜9999、リクエスト内一意 | SCOPE_TAB_002 |
| `page` | 0 以上の整数（負値は 0 に丸めるか 400）| COMMON_001 |
| `folderId` | UUID 形式 + 自分所有 | SCOPE_TAB_004 |
| 検索キーワード（FE→遷移先）| 遷移先（既存検索 API）のサニタイズ・長さ上限・レートリミットに従う | （既存） |

> 検索キーワードは本機能では DB に保存せず、URL クエリとして既存検索ページへ渡すのみ。XSS サニタイズ・長さ上限・レートリミットは**遷移先の既存 search API が責任を持つ**（本機能で重複実装しない）。FE では `encodeURIComponent` 相当（`navigateTo` の `query` で自動エンコード）でクエリを組む。

### 1.4 ウィジェットデータのロール別可視性

- チーム/組織パネルの 8 ウィジェットは F02.2.1 の `viewerRole` / `widgetVisibility`（`min_role`）判定を**そのまま**通す。`viewerRole.isAtLeast(minRole)` が false のウィジェットはサーバー側で `null` / キー省略にする（既存 `DashboardService` の方式踏襲）。
- 課金サマリー・アクセス解析など**管理者限定ウィジェットはメンバーレンズの厳選 8 枚に含めない**（README §2.3）。管理者限定情報がメンバー向けスワイプビューに漏れることはない。
- **L1 管理者レンズ（F10.1.1）**: ADMIN / DEPUTY_ADMIN がレンズトグルを「管理者」に切り替えると `DashboardAdminWidgetGrid`（`ADMIN_*` ウィジェット）が表示される。これらの可視性は `min_role`（3値 enum：PUBLIC/SUPPORTER/MEMBER）ではなく **`StandardVisibility.ADMINS_AND_ABOVE`（ADMIN+DEPUTY 包含）をコードで固定**してゲートする（管理者ウィジェットは F02.2.1 の min_role 管理対象外＝既存 `TEAM_BILLING` 等と同扱い・`isConfigurable()=false`）。サーバー側の可視判定は集合判定 `AccessControlService.isAdminOrAbove`（DEPUTY を確実に含む）を用い、false のウィジェットはレスポンスから省略する。課金・予算等は加えて権限グループ（`BUDGET_VIEW` 等）で DEPUTY を二段ゲートする。詳細は [F10.1.1/02_admin_lens_widgets.md](../F10.1.1_team_org_admin_console/02_admin_lens_widgets.md) §2.1・§4 / [F10.1.1/04_security_authorization.md](../F10.1.1_team_org_admin_console/04_security_authorization.md)。レンズトグルは FE 表示制御に過ぎず、各管理 API は BE で `checkAdminOrAbove` を独立して通す。

### 1.5 統合「要対応」集計の認可（集計バイパス禁止）

- `ScopeActionRequiredFacade` は回覧板/アンケート/出欠の各ドメイン Service を呼ぶが、**各 Service の per-scope 認可を必ず通す**。回覧板の閲覧権がないユーザーには当該区分を 0 件 / 非表示にする（04 §5.3）。集計のためにドメイン認可を迂回しない。

### 1.6 GDPR 連携（退会時）

- `dashboard_scope_tab_order` は PII を含まない**再設定可能な個人設定**。CLAUDE.md §13.12 の二段モデルでは「**即時消去（弱匿名化）区分**」（通知・お気に入り等と同列）に分類する。
- 退会フロー受付直後の `UserAnonymizedEvent`（即時発火）を購読する Listener、または既存 `AccountPurgeService` の弱匿名化フェーズで `DELETE FROM dashboard_scope_tab_order WHERE user_id = ?` を実行する方針を明記する。
- 物理削除で問題なし（情報損失リスクなし）。退会撤回時はユーザーが再度並べ替えれば復旧する。

> **判断記録**: F15.3 のスコープフォルダ本体（`my_scope_folders`）は「強匿名化（猶予対象・最大30日）」だが、本機能の `dashboard_scope_tab_order` は表示順のみで業務整合性に影響しないため「弱匿名化（即時消去）」に分類する。

---

## 2. フロントエンド設計

### 2.1 型定義: `frontend/app/types/dashboard-scope.ts`（新規）

```typescript
export type ScopeTabType = 'TEAM' | 'ORGANIZATION'

export interface ScopeTabItem {
  scopeId: number
  scopeType: ScopeTabType
  name: string
  avatarUrl: string | null
  unreadCount: number
  sortOrder: number
}

export interface ScopeTabPage {
  items: ScopeTabItem[]
  page: number
  pageSize: number
  totalPages: number
  totalCount: number
  hasNext: boolean
  hasPrev: boolean
}

export interface ScopeTabOrderUpdate {
  scopeType: ScopeTabType
  orders: { scopeId: number; sortOrder: number }[]
}

// 要対応ウィジェット
export interface ActionRequiredSummary {
  circulation: { unconfirmedCount: number; items: CirculationActionItem[] }
  survey: { unansweredCount: number; items: SurveyActionItem[] }
  attendance: { unansweredCount: number; items: AttendanceActionItem[] }
  totalActionCount: number
}
```

### 2.2 既存型の型付け: `frontend/app/composables/useDashboardApi.ts`

`useDashboardApi.ts:143` 周辺の `getTeamDashboard` / `getOrganizationDashboard` の戻り値 `unknown` を、新規 `TeamDashboardResponse` / `OrgDashboardResponse` 型へ置き換える（`any` 禁止・`unknown` の野放しを解消）。これらの型は厳選 8 ウィジェットのサマリフィールド（02 §3.3）を含む。

```typescript
// Before
async function getTeamDashboard(teamId: number, statsPeriod?: string) {
  return api<{ data: unknown }>(`/api/v1/dashboard/team/${teamId}${query}`)
}
// After
async function getTeamDashboard(teamId: number, statsPeriod?: string) {
  return api<{ data: TeamDashboardResponse }>(`/api/v1/dashboard/team/${teamId}${query}`)
}
```

### 2.3 composable: `frontend/app/composables/useScopeTabApi.ts`（新規）

```typescript
export function useScopeTabApi() {
  const api = useApi()
  async function getScopeTabs(scopeType: ScopeTabType, page = 0, folderId?: string): Promise<ScopeTabPage> {
    const q = new URLSearchParams({ scopeType, page: String(page) })
    if (folderId) q.set('folderId', folderId)
    const res = await api<{ data: ScopeTabPage }>(`/api/v1/dashboard/scope-tabs?${q}`)
    return res.data
  }
  async function updateOrder(body: ScopeTabOrderUpdate): Promise<void> {
    await api('/api/v1/dashboard/scope-tabs/order', { method: 'PUT', body })
  }
  async function getActionRequired(scopeType: ScopeTabType, scopeId: number): Promise<ActionRequiredSummary> {
    const base = scopeType === 'TEAM' ? `team/${scopeId}` : `organization/${scopeId}`
    const res = await api<{ data: ActionRequiredSummary }>(`/api/v1/dashboard/${base}/action-required`)
    return res.data
  }
  return { getScopeTabs, updateOrder, getActionRequired }
}
```

### 2.4 Pinia Store: `frontend/app/stores/useScopeDashboardStore.ts`（新規）

F20.1 `useNavSettingsStore` + `nav-settings.client.ts` を手本に、localStorage 楽観更新 + サーバー同期。

```typescript
// 状態:
//   activePanel: 'PERSONAL' | 'TEAM' | 'ORGANIZATION'   ← アクティブパネル
//   teamTabPage / orgTabPage: number                     ← タグのページ番号
//   activeFolderId: string | null                        ← フォルダフィルタ
//   selectedTeamId / selectedOrgId: number | null        ← 各パネルで選択中のスコープ
//   tabOrders: Record<ScopeTabType, {scopeId,sortOrder}[]> ← 表示順（楽観）
//
// アクション:
//   loadFromStorage()    : localStorage 'scope-dashboard' から activePanel/ページ/folderId/選択スコープを復元
//   loadTabs(scopeType, page): GET /scope-tabs を呼び正規データで同期
//   reorder(scopeType, orders): 楽観的に tabOrders を更新 → PUT /order → 失敗時ロールバック+トースト
//   setActivePanel(panel): activePanel 更新 + persistToStorage
//   setFolder(folderId)  : activeFolderId 更新 → ページを 0 に戻して loadTabs 再取得
//   persistToStorage()   : 変更のたびに localStorage 保存
```

### 2.5 Plugin: `frontend/app/plugins/scope-dashboard.client.ts`（新規）

`nav-settings.client.ts` と同パターン。起動直後に localStorage から即時復元し、認証済みならサーバー同期。

```typescript
export default defineNuxtPlugin(async () => {
  const store = useScopeDashboardStore()
  store.loadFromStorage()                 // 同期・即時（チラつき防止）
  if (useAuthStore().isAuthenticated) {
    await store.loadTabs('TEAM', store.teamTabPage)  // バックグラウンド同期
  }
})
```

### 2.6 Carousel: `frontend/app/components/dashboard/DashboardScopeCarousel.vue`（新規）

- **swiper ライブラリは使わない**（既存依存に swiper はなく、bundle 肥大を避ける）。`touchstart` / `touchmove` / `touchend` の自前スワイプを実装。
- 3 パネル（個人/チーム/組織）を**同時マウント**し、トラックを `transform: translateX(-N * 100%)` でスライド。`transition: transform .28s ease`。再描画は発生しない（パネルは `v-show` 不使用・常時 DOM 上に存在）。
- **循環遷移**: 個人(0) → チーム(1) → 組織(2) → 個人(0)…。端からのスワイプは「ループ用ゴーストパネル」方式ではなく、`activeIndex` を mod 3 で循環させ、端では `transition` を一時無効化して瞬間ジャンプ → 再有効化（循環の継ぎ目を自然に見せる。03 §3.3）。
- **スワイプ閾値**: 横移動量 > 画面幅の 20% かつ `|Δx| > |Δy| * 1.5`（縦スクロールと誤判定しない）で確定。閾値未満は元位置へ戻す（ばね戻し）。
- **慣性**: フリック速度（`Δx / Δt`）が閾値超なら移動量が小さくてもページ送り。
- props: `activeIndex`（v-model）。emits: `update:activeIndex`。

### 2.7 パネル分割

| コンポーネント | 内容 |
|--------------|------|
| `DashboardPersonalPanel.vue` | **既存 `dashboard.vue` の中身をそのまま内包**（要件 3）。タグ行・検索フォームなし |
| `DashboardTeamPanel.vue` | タグ行（チーム）+ 検索フォーム（チーム）+ 厳選 8 ウィジェット。`selectedTeamId` のダッシュボードを表示 |
| `DashboardOrgPanel.vue` | タグ行（組織）+ 検索フォーム（組織）+ 厳選 8 ウィジェット。`selectedOrgId` のダッシュボードを表示 |

> `dashboard.vue` 自体は「Carousel をマウントするシェル」に再構成し、その個人パネルスロットに従来の内容を移す。ルートは `/dashboard` のまま（URL 変更なし）。

### 2.8 検索フォーム: `frontend/app/components/dashboard/ScopeSearchForm.vue`（新規）

- props: `scopeType: 'TEAM' | 'ORGANIZATION'`。
- 文字入力（`InputText`）+ submit（Enter / 検索ボタン）のみ。**フィルタ UI は持たない**（要件 6）。
- submit で既存検索ページへ遷移:

```typescript
function onSubmit() {
  const path = props.scopeType === 'TEAM' ? '/teams/search' : '/organizations/search'
  navigateTo({ path, query: { keyword: keyword.value.trim() } })
}
```

### 2.9 検索ページ小改修

`frontend/app/pages/teams/search.vue` / `organizations/search.vue` は現状 URL クエリ未対応。`onMounted` で `route.query.keyword` を読み、存在すれば検索フォームの初期値にセットし初期検索を実行する。

```typescript
onMounted(() => {
  const kw = route.query.keyword
  if (typeof kw === 'string' && kw.length > 0) {
    searchParams.value.keyword = kw
    fetchTeams() // 既存の検索実行関数
  }
})
```

> 既存の `searchParams.value.keyword` / `fetchTeams()` を流用（teams/search.vue 確認済み）。地域・テンプレートのフィルタは従来どおりページ内 UI で操作。

### 2.10 タグ行 UI: `frontend/app/components/dashboard/ScopeTabBar.vue`（新規）

- 上位 6 件をタグ（チップ）として横並び表示。タップで `selectedTeamId` / `selectedOrgId` を切替（パネル内容を該当スコープに更新）。
- ページ送り `‹ 1/N ›`: 左右の矢印で `page` を ±1（`hasPrev` / `hasNext` で活性制御）。
- フォルダフィルタ: 左端に `📁[未分類▾]` ドロップダウン（F15.3 の `my_scope_folders` 一覧 + 「すべて」）。選択で `setFolder()` → ページ 0 に戻して再取得。
- 表示順設定ダイアログ起動ボタン（⚙）。
- タグ行は**横スクロール禁止**（6 件固定 + ページ送り）。これによりカルーセルの左右スワイプとタグ横スクロールのジェスチャ競合を回避（03 §3.3）。

### 2.11 表示順設定ダイアログ: `ScopeTabOrderDialog.vue`（新規）

- `vuedraggable@4.1.0`（既存依存）で全所属スコープをドラッグ並べ替え。
- 確定で `store.reorder(scopeType, orders)` → `PUT /scope-tabs/order`（楽観更新 + 失敗ロールバック）。
- 多数スコープ（数十件）の場合もダイアログ内はスクロール可能なリストで全件並べ替え可能。

---

## 3. UX設計

### 3.1 スワイプ操作（モバイル中心）

| 操作 | 挙動 |
|------|------|
| 左フリック | 次パネルへ（個人→チーム→組織→個人…循環）|
| 右フリック | 前パネルへ（個人→組織→チーム→個人…循環）|
| 閾値未満のドラッグ | 元パネルへばね戻し |
| 縦スクロール | パネル内コンテンツのスクロール（`|Δy| > |Δx|` で縦優先判定）|

### 3.2 デバイス別操作系（要件 2）

| デバイス | 操作 |
|---------|------|
| モバイル | 左右フリック + 下部ドットインジケーター（タップでも切替）|
| PC | 上部セグメントトグル（[個人][チーム][組織]）+ 左右矢印ボタン + キーボード ←→ |
| 共通 | ドット / セグメントの直接タップで任意パネルへジャンプ |

- キーボード: `←` = 前パネル、`→` = 次パネル。フォーカスが入力フィールド（検索フォーム等）にあるときは矢印キーを奪わない（`event.target` が入力要素なら無視）。
- PC のセグメントトグルは `role="tablist"` / 各パネルは `role="tabpanel"`（03 §3.8）。

### 3.3 循環スワイプの端処理・ジェスチャ競合

| 論点 | 対応 |
|------|------|
| 循環の継ぎ目（組織→個人）| `activeIndex` を mod 3 で循環。端ジャンプ時のみ `transition` を 1 フレーム無効化して瞬間移動 → 再有効化し継ぎ目を自然化 |
| 左右スワイプ vs タグ横スクロール | タグ行は**横スクロールを持たせない**（6 件固定 + ページ送り矢印）。カルーセルの水平ジェスチャと競合しない |
| 左右スワイプ vs パネル内横スクロール要素（カレンダー等）| 横スクロール領域上では `touchmove` の `stopPropagation`、または当該領域を縦積みレイアウトにして横スクロールを排除 |
| スワイプ vs 縦スクロール | `|Δx| > |Δy| * 1.5` の方向判定で横/縦を排他選択 |

### 3.4 タグ：6 件 + ページ送り（要件 5）

- 上位 6 件を表示、7 件目以降は 6 件単位でページ送り（`‹ page/total ›`）。
- 並び順は `dashboard_scope_tab_order` の保存順 → 未保存は `last_accessed_at` 降順末尾補完（01 §6-5）。
- ⚙ から表示順設定ダイアログでドラッグ並べ替え可能。

### 3.5 フォルダフィルタ（F15.3 流用・任意）

- タグ行のフォルダドロップダウンで「すべて / 各フォルダ」を選択。
- フォルダ選択時は当該フォルダの `my_scope_folder_items` に含まれる scope のみをタグ対象集合とし、その中で 6 件 + ページ送り。
- フォルダフィルタ × ページ送りの相互作用: フォルダを切り替えたら**ページを 0 にリセット**（フィルタ後の集合が変わるため。02 §3.1 並び順ロジック step 3）。

### 3.6 エッジケース

| ケース | 対応 |
|-------|------|
| 所属 0 件（チーム/組織なし）| タグ行に「所属しているチーム/組織がありません」+ 検索フォームへ誘導。パネル本体は空状態 |
| 所属 1 スコープのみ | タグ 1 件表示、ページ送り非活性。自動でそのスコープを選択 |
| 多数スコープ（数十件）| 6 件 + ページ送り。表示順ダイアログで全件並べ替え |
| 退会 / 権限喪失したスコープ | タグ取得時に現在の所属集合と突合し自動除外（01 §6-6）。選択中スコープが消えたら先頭スコープへフォールバック |
| 表示順の同時更新競合（複数端末）| 最後の書き込み勝ち（02 §3.2）。表示順のみの影響で実害なし。次回 `loadTabs` でサーバー値に収束 |
| タグページ超過（page > total_pages）| `items=[]` を返し、FE は前ページへ戻す（02 §3.1）|
| フォルダに 1 件も scope がない | 空状態 + 「このフォルダにスコープがありません」。フォルダ選択解除を促す |
| オフライン（タグ取得失敗）| localStorage の最後の選択スコープ + キャッシュで暫定表示。エラートースト。握りつぶさない |
| 個人パネルに検索フォーム | **出さない**（§3.9）|

### 3.7 モバイル対応

- 上部セグメントの代わりに左右フリック + 下部ドット。
- 検索フォームはアイコンタップで入力フィールドを展開（省スペース）。
- タグ行は 6 件がはみ出す画面幅ではチップを縮小表示し、ページ送り矢印を常時表示。

### 3.8 アクセシビリティ

| 観点 | 対応 |
|------|------|
| キーボード | ←→ でパネル切替（入力フォーカス時は無効）。セグメントトグルは Tab フォーカス可・Enter/Space で選択 |
| スクリーンリーダー | パネルコンテナ `role="tablist"`、各セグメント `role="tab"` + `aria-selected`、各パネル `role="tabpanel"` + `aria-labelledby`。パネル切替時に `aria-live="polite"` で「チームパネルに切り替えました」を通知 |
| タグ | 各タグは `role="button"` + `aria-pressed`（選択中）。ページ送り矢印に `aria-label`（前のページ/次のページ）|
| prefers-reduced-motion | `@media (prefers-reduced-motion: reduce)` 時は `translateX` のトランジションを無効化し即時切替（慣性アニメも無効）|
| アバター | アイコン画像に `alt`（スコープ名）。null 時のイニシャルアバターにも `aria-label` |

### 3.9 個人パネルに検索フォームを出さない判断

要件 6 は「チーム検索」「組織検索」フォーム。**個人パネルには出さない**。

> **判断記録**: 個人パネルは自分自身のダッシュボードであり、「他のチーム/組織を探す」検索は文脈に合わない（個人には所属スコープ選択も検索も不要）。検索フォームはチーム/組織パネル（＝「所属を探す/増やす」文脈）にのみ配置する。タグ行も同様に個人パネルには出さない（04 §1）。

---

## 4. i18n 追加キー（6 言語）

全 6 言語（`ja` / `en` / `zh` / `ko` / `es` / `de`）に追加。トグル/検索/操作ラベルは `common.json`、ウィジェット見出しは機能別ファイル。直書き禁止。

### 4.1 `locales/{lang}/common.json`

```json
{
  "scopeDashboard": {
    "tabs": { "personal": "個人", "team": "チーム", "organization": "組織" },
    "prevPanel": "前のパネル",
    "nextPanel": "次のパネル",
    "switchedTo": "{name}パネルに切り替えました",
    "searchTeam": "チームを検索",
    "searchOrganization": "組織を検索",
    "tagBar": {
      "filterAll": "すべて",
      "folderFilter": "フォルダで絞り込み",
      "prevPage": "前のページ",
      "nextPage": "次のページ",
      "reorder": "表示順を変更",
      "empty": "所属しているチーム/組織がありません",
      "folderEmpty": "このフォルダにスコープがありません"
    },
    "orderDialog": { "title": "タグの表示順", "save": "保存", "cancel": "キャンセル", "saveError": "表示順の保存に失敗しました" }
  }
}
```

### 4.2 ウィジェット見出し（機能別 json：`dashboard.json` 等）

```json
{
  "swipeWidgets": {
    "upcoming": "今後の予定",
    "timeline": "タイムライン",
    "bulletin": "掲示板",
    "blog": "ブログ",
    "chat": "チャット",
    "calendar": "カレンダー",
    "todo": "TODO",
    "actionRequired": {
      "title": "要対応",
      "circulation": "回覧板",
      "survey": "アンケート",
      "attendance": "出席確認",
      "unconfirmed": "{count}件未確認",
      "unanswered": "{count}件未回答",
      "empty": "対応が必要な項目はありません"
    },
    "emptyState": "まだ投稿がありません"
  }
}
```

> 未翻訳言語はとりあえず日本語と同値で追加し、後で翻訳（CLAUDE.md i18n ルール）。

---

## 5. 精査チェックリスト（実装時の確認項目）

### セキュリティ
- [ ] `SecurityUtils.getCurrentUserId()` 使用（self-access のみ・userId 非露出）
- [ ] `PUT /scope-tabs/order` で非所属 scopeId 混入を全体 403（SCOPE_TAB_001）
- [ ] `folderId` は自分所有フォルダのみ（他人 404）
- [ ] チーム/組織パネルは既存 `checkMembership` を通す
- [ ] 要対応集計は各ドメイン認可をバイパスしない
- [ ] 管理者限定ウィジェットを 8 枚に含めない
- [ ] 退会時 `dashboard_scope_tab_order` 削除（弱匿名化区分）

### 機能
- [ ] タグ 6 件 + ページ送り（6 件単位）
- [ ] 表示順未保存スコープを `last_accessed_at` 降順で末尾補完
- [ ] 退会/権限喪失スコープの自動除外
- [ ] フォルダ切替でページ 0 リセット
- [ ] 循環スワイプの端処理
- [ ] 組織スコープのタイムライン/掲示板/チャット/ブログ/カレンダーを新規実装（空なら空配列で正直に返す）

### フロントエンド
- [ ] 3 パネル同時マウント + translateX（再描画なし）
- [ ] swiper ライブラリ不使用（自前 touch ハンドラ）
- [ ] スワイプ vs 縦スクロール / タグ横スクロールのジェスチャ競合回避
- [ ] PC: セグメント + 矢印 + キーボード ←→（入力フォーカス時は無効）
- [ ] `useDashboardApi.ts:143` の `unknown` を型付与
- [ ] 検索フォームはキーワードのみ・`navigateTo` で遷移
- [ ] `teams/search.vue` / `organizations/search.vue` の `onMounted` で `route.query.keyword` 初期検索
- [ ] store + plugin（localStorage 楽観更新 + サーバー同期）
- [ ] 個人パネルに検索フォーム・タグ行を出さない
- [ ] i18n 6 言語追加（直書きゼロ）
- [ ] prefers-reduced-motion でアニメ無効化・ARIA tablist/tab/tabpanel

---

## 6. 1 回目精査ログ

設計ドラフト作成後、広く 5 観点（不備 / セキュリティ / ユーザビリティ / 保守性 / 見落とし）で自己レビューを実施。発見事項は本書本体に反映済み。

| # | 観点 | 発見事項 | 反映先 |
|---|------|---------|--------|
| 1 | ユーザビリティ | タグ 7 件超のページ送り × フォルダフィルタの相互作用が未定義だった（フォルダ切替後にページ番号が残ると空表示になる）| §3.5 / 02 §3.1 step3 で「フォルダ切替時ページ 0 リセット」を明示 |
| 2 | ユーザビリティ | 所属 0 件 / 1 スコープのみ / 多数スコープ時の表示が未定義 | §3.6 エッジケース表に 3 ケース追加 |
| 3 | セキュリティ | 退会・権限喪失したスコープのタグ残存（IDOR 的に他スコープ名が漏れる懸念）| 01 §6-6 + §3.6 + 02 §3.1 step4「現在の所属集合と突合し自動除外」 |
| 4 | ユーザビリティ | スワイプとタグ横スクロールのジェスチャ競合 | §3.3 + §2.10「タグ行は横スクロール禁止（6 件固定 + ページ送り）」で根治 |
| 5 | 不備 | 循環スワイプの端処理（組織→個人の継ぎ目）が曖昧だった | §2.6 / §3.3「mod 3 循環 + 端ジャンプ時 transition 一時無効化」 |
| 6 | 保守性 | 横スワイプ widget_key が F02.2 詳細ページの key と衝突し、片方の非表示がもう片方に波及する懸念 | 04 §2.1「`SWIPE_` プレフィックスで名前空間分離」で根治 |
| 7 | 見落とし | 個人パネルに検索フォーム / タグ行を出すか未決定だった | §3.9 / 04 §1「個人には出さない」と明記 + 判断記録 |
| 8 | セキュリティ | `PUT order` で一部 scopeId が非所属の場合の挙動（部分適用 vs 全体拒否）未定義 | §1.2 / 02 §3.2「1 件でも非所属なら全体 403」 |
| 9 | 不備 | 組織スコープのタイムライン/掲示板/チャット/ブログ/カレンダーが未実装なのを「フラグで握りつぶす」リスク | 04 §4 注記 + §5 チェックリスト「空なら空配列で正直に返す」（対処療法禁止原則）|
| 10 | 保守性 | Flyway 番号を架空にしないための実値確認 | 01 §5「2026-05-30 時点 origin/main 最新 V9.180、実装時に +1 採番」|
| 11 | セキュリティ | フォルダフィルタの `folderId` が他人のフォルダを参照できる IDOR | §1.1 / §1.2 / 02 §2.3「自分所有フォルダのみ・他人 404」|
| 12 | ユーザビリティ | 検索キーワードのサニタイズ責任範囲が曖昧 | §1.3「遷移先既存 search API が責任。FE は navigateTo 自動エンコードのみ」|
| 13 | 見落とし | F02.2 `TEAM_MEMBER_ATTENDANCE` と統合要対応の出欠が混同される | 04 §5.4「自分の未回答 vs チーム全体集計、key 分離」|

---

## 7. 2 回目精査ログ

1 回目で改善された設計書に対し、より深い境界条件・同時実行・障害時挙動・パフォーマンス・GDPR・多言語・アクセシビリティの観点で再精査。発見事項は本書本体に反映済み。

| # | 観点 | 発見事項 | 反映先 |
|---|------|---------|--------|
| 1 | 境界条件 | 選択中スコープがタグから消えた（退会直後）場合のパネル表示 | §3.6「選択中スコープが消えたら先頭スコープへフォールバック」 |
| 2 | 境界条件 | `page > total_pages` の超過リクエスト | 02 §3.1 / §3.6「`items=[]` を返し FE は前ページへ戻す」 |
| 3 | 同時実行 | 複数端末でのタグ表示順同時更新の競合 | 02 §3.2 / §3.6「最後の書き込み勝ち。表示順のみで実害なし。次回 loadTabs で収束」 |
| 4 | 障害時挙動 | オフライン / タグ取得失敗時のフォールバック（握りつぶし禁止）| §3.6「localStorage キャッシュ暫定表示 + エラートースト。握りつぶさない」 |
| 5 | 障害時挙動 | 要対応集計で 1 ドメインが例外を投げた場合 | 04 §5.3 / 02 §3.4：`CompletableFuture` 並行集計で当該区分のみ縮退（0 件表示）し他区分は出す。全体を落とさない。例外は隠さずログ記録 |
| 6 | パフォーマンス | 多数スコープ時のタグ取得・要対応集計の N+1 | 02 §3.1 / §3.4 / 04 §5.3「IN 句バッチ取得」「6 件/ページで上位制限」「Valkey キャッシュ `scope:action:*`」 |
| 7 | パフォーマンス | 8 ウィジェット × 3 パネル同時マウントの初期ロード負荷 | 02 §3.3「2 段階ロード：第1段階サマリ + 第2段階ビューポート遅延取得（F02.2 思想）」。非アクティブパネルは初回はサマリのみ |
| 8 | パフォーマンス | 非表示ウィジェットのデータ取得 | §1.4 / 04 §6「`is_visible=FALSE` はサーバーでスキップ・キー省略（F02.2 最適化踏襲）」 |
| 9 | GDPR | 退会時の `dashboard_scope_tab_order` 削除区分（弱 vs 強匿名化）| §1.6「弱匿名化（即時消去）区分。F15.3 フォルダ本体（強匿名化）と区別」+ 01 §3 原則4 |
| 10 | 多言語 | パネル切替の aria-live メッセージのプレースホルダ | §4.1 `switchedTo: "{name}パネルに切り替えました"`（name 補間） |
| 11 | アクセシビリティ | prefers-reduced-motion でスワイプ慣性アニメを無効化 | §3.8「reduce 時は translateX トランジション・慣性とも無効、即時切替」 |
| 12 | アクセシビリティ | スクリーンリーダーでのタブ構造・選択状態 | §3.8 tablist/tab/tabpanel + `aria-selected` / `aria-pressed` / ページ送り `aria-label` |
| 13 | 境界条件 | 1 ユーザーが同一スコープに MEMBER と SUPPORTER 両方を持つケースのタグ | タグは scope_id 単位で一意（`uq_dsto_user_scope`）。ロール判定はパネルデータ取得時に F02.2.1 が解決（権限が広い MEMBER 優先・F19.1 と同方針）。タグ自体は 1 件 |
| 14 | 境界条件 | フォルダに退会済みスコープが残存（F15.3 側のゴースト）| フォルダフィルタ後も §3.6 の所属集合突合で自動除外。F15.3 のフォルダ item は読み取りのみで本機能から書き換えない |
| 15 | 保守性 | `dashboard.vue` を Carousel シェル化する際の URL 維持 | §2.7「ルートは `/dashboard` のまま。個人パネルスロットに従来内容を移設」 |
| 16 | パフォーマンス | タグバッジ `unread_count` のリアルタイム性 | 02 §3.1「Valkey キャッシュ + 短 TTL」。厳密なリアルタイムは不要（バッジは概数で許容） |

すべての発見事項は本書本体（§1〜§4）および 01 / 02 / 04 に反映済み。**未解決事項: なし**。実装着手可能。
