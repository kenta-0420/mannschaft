# リファクタリング第1弾 概要

## 背景・目的

2026年5月時点でMannschaftは1,300行超のServiceクラス、700行超のcomposable、900行超のVueページなど、
単一責任の原則を逸脱した巨大ファイルが複数存在していた。
これらを機能ドメイン単位に分割し、可読性・テスト容易性・並行開発効率を向上させることを目的とする。

## 対象ファイル（第1弾）

| 対象 | 変更前行数 | 課題 |
|------|-----------|------|
| `backend/.../actionmemo/service/ActionMemoService.java` | 1,307行 | CRUD・投稿・集計・管理者機能が1クラスに混在 |
| `frontend/app/composables/useParkingApi.ts` | 707行 | 66個の関数が1 composableに集中 |
| `frontend/app/pages/organizations/[id]/webhooks.vue` | 905行 | 送信/受信Webhook・APIキーの3機能が1ファイルに混在 |

---

## 1. ActionMemoService 分割方針

### 分割前の課題

- CRUD系・TODO紐付け系・投稿系・チーム/組織スコープ解決・集計・管理者機能の6ドメインが1クラスに混在
- TimelinePostRepository, TodoRepository, UserRoleRepository, TeamRepository 等8つのRepositoryに依存
- テスト対象が不明確で、モックが複雑化していた

### 分割後の構成（5クラス）

```
com.mannschaft.app.actionmemo.service/
├── ActionMemoService.java            （CRUD中心）
│   createMemo / getMemo / updateMemo / deleteMemo / listMemos
│   linkTodo / getMemoAuditLogs
│
├── ActionMemoPublishingService.java  （タイムライン投稿）
│   publishDaily / publishToTeam / publishDailyToTeam
│   buildPublishDailyContent / buildPublishToTeamContent
│
├── ActionMemoScopeService.java       （スコープ解決）
│   getAvailableTeams / getAvailableOrgs / validateTodoScope
│
├── ActionMemoAnalyticsService.java   （集計・分析）
│   getMoodStats
│
└── ActionMemoAdminService.java       （管理者機能）
    revertTodoCompletion / listTeamMemberMemos
```

### 設計上の注意点

- 既存の `ActionMemoController` からの呼び出しは変更不要（Serviceのインターフェースを維持）
- Controller側が複数のServiceをインジェクトする形に変わる
- `@Transactional` は各Serviceクラスレベルで `readOnly = true`、更新メソッドで個別上書き
- タイムラインへの投稿（TimelinePostRepository）は将来的にイベント駆動化候補

---

## 2. useParkingApi.ts 分割方針

### 分割前の課題

- 駐車スペース・応募・募集・設定・サブリース・来客予約・来客定期・ウォッチリスト・個人車両の9ドメインが1 composableに集中
- 66個の関数が混在し、インポート時に不要な関数まで読み込まれる
- URLSearchParamsの処理が各関数で重複

### 分割後の構成（10ファイル）

```
frontend/app/composables/parking/
├── useParkingApiBase.ts              （共通ユーティリティ：buildBase, URLビルダー）
├── useParkingSpacesApi.ts            （駐車スペース CRUD + 割当/解除/整備/履歴）
├── useParkingApplicationsApi.ts      （応募 CRUD + 承認/拒否/抽選）
├── useParkingListingsApi.ts          （募集 CRUD + 申込/譲渡）
├── useParkingSettingsApi.ts          （設定取得/更新 + 統計）
├── useParkingSubleaseApi.ts          （サブリース CRUD + 申込/承認/支払/終了）
├── useParkingVisitorReservationsApi.ts （来客予約 CRUD + 承認/拒否/チェックイン/完了）
├── useParkingVisitorRecurringApi.ts  （来客定期 CRUD）
├── useParkingWatchlistApi.ts         （ウォッチリスト CRUD）
└── usePersonalVehiclesApi.ts         （個人車両 CRUD）
```

後方互換のため、既存の `useParkingApi.ts` から全サブ composable を re-export する。

### 設計上の注意点

- 既存の呼び出し箇所（`useParkingApi()`）は変更不要（re-export で透過的に移行）
- `usePersonalVehiclesApi` のみスコープなし（`/api/v1/users/me/vehicles`）で他と異なる

---

## 3. webhooks.vue 分割方針

### 分割前の課題

- 送信Webhook管理・受信Webhook管理・APIキー管理の3機能が1 Vueファイルに混在
- script setupが450行超で状態管理・フォーム管理・API処理が混在
- タブ切替で毎回全機能のコードが評価される

### 分割後の構成

```
frontend/app/pages/organizations/[id]/
└── webhooks.vue                      （タブ管理のみ・3子コンポーネントを配置）

frontend/app/components/webhooks/
├── WebhookOutgoingTab.vue            （送信Webhook管理）
│   ├── EndpointFormDialog.vue
│   └── WebhookDeliveryLogPanel.vue
├── WebhookIncomingTab.vue            （受信Webhook管理）
│   ├── IncomingWebhookFormDialog.vue
│   └── TokenDisplay.vue
└── WebhookApiKeyTab.vue             （APIキー管理）
    ├── ApiKeyFormDialog.vue
    └── ApiKeyIssuedResultModal.vue
```

### 設計上の注意点

- 親の `webhooks.vue` の責務はタブ管理（`activeTab`）のみに縮小
- 各タブコンポーネントは `orgId` を props で受け取り、API呼び出しは自己完結
- フォームバリデーション（Zodスキーマ）は各タブ内に移動
- 共通の ConfirmDialog コンポーネント化で削除確認UIを統一

---

## 実施時期

| フェーズ | 対象 | 実施時期 |
|---------|------|---------|
| 第1弾 | ActionMemoService / useParkingApi / webhooks.vue | 2026-05-16 |

## 参考

- OpenAPI Generator 導入（PR #634, 2026-05-16 main マージ）との連携: 型定義の移行は別途実施
- 本リファクタリングはロジック変更なし・振る舞い変更なし（純粋な構造整理）
