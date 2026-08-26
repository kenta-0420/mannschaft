# F22.1: API設計

> **ステータス**: 🟢 設計確定
> **最終更新**: 2026-05-30
> **関連ドキュメント**:
> - [README.md](./README.md) — 機能概要
> - [01_db_design.md](./01_db_design.md) — `dashboard_scope_tab_order`
> - [04_widgets.md](./04_widgets.md) — 厳選 8 ウィジェット・統合要対応集計
> - [F02.2_dashboard.md](../F02.2_dashboard.md) — 既存 team/org dashboard API（拡張対象）
> - [docs/security/README.md](../../security/README.md) — 認可横断方針

---

## 1. エンドポイント一覧

### 1.1 新規エンドポイント（本機能で追加）

| メソッド | パス | 認証 | ロール | 説明 |
|---------|------|------|-------|------|
| GET | `/api/v1/dashboard/scope-tabs` | 必須 | 全認証ユーザー | 表示順適用済みの所属スコープ（タグ）一覧を 6 件/ページで返す |
| PUT | `/api/v1/dashboard/scope-tabs/order` | 必須 | 全認証ユーザー | タグ表示順の一括更新（自分の所属スコープのみ） |

### 1.2 既存エンドポイント（本機能で拡張）

| メソッド | パス | 状態 | 本機能での扱い |
|---------|------|------|--------------|
| GET | `/api/v1/dashboard/team/{teamId}` | 実装済み（`DashboardController.java:351`）| 厳選 8 ウィジェット分のフィールド追加（§3.3 明細表） |
| GET | `/api/v1/dashboard/organization/{orgId}` | 実装済み（`DashboardController.java:364`）| 同上（組織スコープの未実装フィールドを新設） |
| GET | `/api/v1/dashboard/widgets` | 実装済み | チーム/組織パネルのウィジェット可視性・並び順取得にそのまま使用（変更なし） |

### 1.3 既存検索エンドポイント（遷移先・変更なし）

| メソッド | パス | 本機能での扱い |
|---------|------|--------------|
| — | `/teams/search`（FE ページ）| 検索フォーム submit で `?keyword=` 付きで遷移（FE 側で `onMounted` 初期検索対応＝03 §2.9）|
| — | `/organizations/search`（FE ページ）| 同上 |

> 検索の API 本体（地域・テンプレート等のフィルタ込み）は既存のチーム/組織検索 API をそのまま使う。本機能では新規検索 API を**追加しない**。

---

## 2. 認可設定

### 2.1 SecurityConfig 追加ルール

```java
// 既存 securityFilterChain に追記
.requestMatchers(HttpMethod.GET, "/api/v1/dashboard/scope-tabs").authenticated()
.requestMatchers(HttpMethod.PUT, "/api/v1/dashboard/scope-tabs/order").authenticated()
```

> 既存の `/api/v1/dashboard/**` が `authenticated()` でカバーされている場合は追記不要。明示ルールがあれば本パスを含むことを確認する。

### 2.2 コントローラ二重防御（@PreAuthorize）

```java
// DashboardScopeTabController
@PreAuthorize("isAuthenticated()")
```

### 2.3 サービス層の所属検証（IDOR 防止の本丸）

- `GET /scope-tabs`: `SecurityUtils.getCurrentUserId()` で自分の ID を取得し、**自分が所属するスコープのみ**返す。リクエストに `userId` を露出しない。
- `PUT /scope-tabs/order`: body の `orders[].scopeId` が**すべて自分の所属スコープ**であることを `AccessControlService.checkMembership(userId, scopeId, scopeType)` で検証。非所属の scope_id が 1 件でも含まれていれば全体を 403 で拒否（部分適用しない）。
- フォルダフィルタ（`folderId`）: 指定フォルダが**自分の所有**（`my_scope_folders.user_id = currentUserId`）であることを検証。他人のフォルダ ID は 404。

---

## 3. リクエスト／レスポンス仕様

### 3.1 GET /api/v1/dashboard/scope-tabs

**説明**: ログインユーザーが所属するチーム（または組織）を、表示順適用済みで 6 件/ページ返す。タグ行のデータソース。

**クエリパラメータ**

| パラメータ | 型 | 必須 | デフォルト | 説明 |
|-----------|---|------|-----------|------|
| `scopeType` | String | Yes | — | `TEAM` / `ORGANIZATION` |
| `page` | Integer | No | 0 | 0 始まりのページ番号（1 ページ = 6 件） |
| `folderId` | UUID | No | — | F15.3 フォルダ ID。指定時は当該フォルダに割り当てられた scope のみに絞り込み（自分所有のフォルダのみ） |

> 1 ページの件数は固定 **6 件**（要件 5：上位 6 件ずつ表示）。クライアントから `size` は受け取らない。

**並び順ロジック（サービス層）**:
1. `dashboard_scope_tab_order` の保存済み行を `sort_order` 昇順で取得。
2. 未保存の所属スコープを `team_memberships.last_accessed_at`（組織は相当の最終アクセス）降順で末尾に補完。
3. `folderId` 指定時は、`my_scope_folder_items` に含まれる `scope_id` のみにフィルタ（フィルタは並び順適用の**前**＝対象集合を絞ってから 6 件/ページ）。
4. 現在の所属集合と突合し、退会/権限喪失スコープを除外。
5. `page * 6` から 6 件を切り出す。

**レスポンス（200 OK）**:
```json
{
  "data": {
    "items": [
      {
        "scope_id": 12,
        "scope_type": "TEAM",
        "name": "開発チーム",
        "avatar_url": "https://.../team12.png",
        "unread_count": 4,
        "sort_order": 0
      },
      {
        "scope_id": 7,
        "scope_type": "TEAM",
        "name": "営業部",
        "avatar_url": null,
        "unread_count": 0,
        "sort_order": 1
      }
    ],
    "page": 0,
    "page_size": 6,
    "total_pages": 3,
    "total_count": 14,
    "has_next": true,
    "has_prev": false
  }
}
```

| フィールド | 説明 |
|----------|------|
| `items[].scope_id` | チーム / 組織 ID |
| `items[].name` | 表示名（teams.name / organizations.name） |
| `items[].avatar_url` | アイコン URL（なければ null → FE でイニシャルアバター） |
| `items[].unread_count` | 当該スコープの未読合計（タイムライン/掲示板/チャット + 要対応件数の総和。バッジ表示用。Valkey キャッシュ推奨） |
| `items[].sort_order` | 現在の表示順 |
| `total_count` | フィルタ適用後の所属スコープ総数 |
| `total_pages` | `ceil(total_count / 6)` |

**エッジケース**:
- 所属 0 件: `items=[]`, `total_count=0`, `total_pages=0`, `has_next=false`, `has_prev=false`。
- `page` が `total_pages` 超過: `items=[]` を返す（エラーにしない。FE は前ページへ戻す）。

---

### 3.2 PUT /api/v1/dashboard/scope-tabs/order

**説明**: タグの表示順を一括更新（UPSERT）。ドラッグ並べ替え確定時に呼ぶ。

**リクエスト**:
```json
{
  "scopeType": "TEAM",
  "orders": [
    { "scopeId": 7,  "sortOrder": 0 },
    { "scopeId": 12, "sortOrder": 1 },
    { "scopeId": 3,  "sortOrder": 2 }
  ]
}
```

**バリデーション**:

| 項目 | ルール | エラー |
|------|-------|-------|
| `scopeType` | 必須、`TEAM` / `ORGANIZATION` のいずれか | SCOPE_TAB_003 |
| `orders` | 必須、1〜200 要素（非所属混入チェックは所属検証で） | COMMON_001 |
| `orders[].scopeId` | 必須、すべて**自分の所属スコープ**であること | SCOPE_TAB_001 |
| `orders[].sortOrder` | 必須、0〜9999、`orders` 内で重複しないこと | SCOPE_TAB_002 |

**処理**:
- `INSERT ... ON DUPLICATE KEY UPDATE sort_order = VALUES(sort_order)`（`uq_dsto_user_scope` キー）。
- 1 リクエスト内は単一 `@Transactional`（dashboard ドメイン内に閉じる。所属検証のみ team/user ドメイン Service 呼び出し）。
- 楽観ロック不要（最後の書き込み勝ち。同一ユーザーが複数端末で同時並べ替えする確率は低く、影響は表示順のみ＝03 §3.6 で UX 補足）。

**レスポンス**: `204 No Content`

---

### 3.3 既存 team/org dashboard API の拡張明細

チーム/組織パネルの厳選 8 ウィジェット（04 参照）のうち、**既存 `GET /dashboard/team/{teamId}` / `/organization/{orgId}` が既に返すもの**と、**新規追加が必要なもの**を整理する。

#### 現状（実装済み・`DashboardService.java`）

| ウィジェット | チーム | 組織 | 備考 |
|------------|:-----:|:----:|------|
| ①今後の予定（UPCOMING_EVENTS） | ✅ teamUpcomingEvents | ⚠️ 未実装 | 組織スコープのイベント集約が未実装 |
| ②タイムライン（LATEST_POSTS） | ✅ teamLatestPosts | ⚠️ 未実装 | |
| ③掲示板（UNREAD_THREADS） | ✅ teamUnreadThreads | ⚠️ 未実装 | |
| ④ブログ | ⚠️ 未実装 | ⚠️ 未実装 | 両スコープとも未実装 |
| ⑤チャット | ⚠️ 未実装（unreadThreads にチャット含むが独立ウィジェットなし）| ⚠️ 未実装 | |
| ⑥カレンダー | ⚠️ 未実装（teamUpcomingEvents で代替）| ⚠️ 未実装 | 月内ドット等のサマリ未実装 |
| ⑦TODO | ✅ teamTodo | ✅ orgTodo | |
| ⑧統合「要対応」（回覧板/アンケート/出欠）| ⚠️ 出欠の実データ未実装・回覧板/アンケート未実装 | ⚠️ 未実装 | **本機能の中核。新規集計が必要** |

#### 拡張方針：2 段階ロード（F02.2 思想踏襲）

F02.2 §4 の 2 段階ロード戦略（軽量サマリ + ビューポート遅延取得）を踏襲する。

- **第 1 段階（軽量サマリ）**: 既存 `GET /dashboard/team/{teamId}` / `/organization/{orgId}` に、各ウィジェットの**件数サマリと直近 1〜3 件**のみを追加する（下表「サマリ追加フィールド」）。
- **第 2 段階（遅延取得）**: 各ウィジェットの「もっと見る」相当は、ビューポート進入時に**ウィジェット個別エンドポイント**で取得する。本機能では新規に最小限の個別エンドポイントを定義する（下表「個別エンドポイント新設」）。

> **判断記録**: 「既存レスポンスへフィールド追加」と「個別エンドポイント新設」の双方を採用する。理由＝第 1 段階で全 8 枚の重いリストを返すと初期表示が遅く、スワイプの軽快さ（要件 1）を損なう。サマリのみ一括取得 + 詳細は遅延取得が F02.2 と整合し最適。

#### サマリ追加フィールド（第 1 段階・既存レスポンスへ追加）

| ウィジェット | 追加フィールド（team / org 共通の形） | データソース |
|------------|--------------------------------------|------------|
| ④ブログ | `teamLatestBlogPosts` / `orgLatestBlogPosts`: 直近 3 件（id/title/author/publishedAt）| blog ドメイン Service |
| ⑤チャット | `teamChatSummary` / `orgChatSummary`: `{ total_unread, channels:[{id,name,unread_count,last_message_preview}]（直近3）}` | chat ドメイン Service（既存 unread 集計流用） |
| ⑥カレンダー | `teamCalendarSummary` / `orgCalendarSummary`: `{ events_today, events_this_week, next_event, days_with_events }` | schedule ドメイン Service（F02.2 `/dashboard/calendar` と同形） |
| ⑧要対応 | `teamActionRequired` / `orgActionRequired`: §3.4 の集計 JSON | circulation / survey / attendance ドメイン Service（ファサード集約）|
| ①②③（組織のみ）| `orgUpcomingEvents` / `orgLatestPosts` / `orgUnreadThreads`: チームと同形を組織スコープで新設 | schedule / timeline / bulletin Service |

> いずれも `is_visible = FALSE`（F02.2.1 のロール不可視 / ユーザー非表示）のウィジェットはサーバー側でスキップし、レスポンスにキーを含めない（F02.2 の最適化と同じ）。

#### 個別エンドポイント新設（第 2 段階・遅延取得）

| メソッド | パス | 説明 |
|---------|------|------|
| GET | `/api/v1/dashboard/team/{teamId}/action-required` | 統合「要対応」の詳細（回覧板/アンケート/出欠の直近アイテム + 件数。ページング可）|
| GET | `/api/v1/dashboard/organization/{orgId}/action-required` | 同上（組織スコープ）|

> ④ブログ・⑤チャット・⑥カレンダーの「もっと見る」は、**既存の機能別 API（ブログ一覧 / チャット / カレンダー）へ遷移**するため個別エンドポイントを新設しない（既存を流用）。新設は「要対応」のみ（複数ドメイン集約のため既存 API では代替不可）。

---

### 3.4 統合「要対応」集計（中核）

回覧板（circulation）・アンケート（survey）・出席確認（attendance）の 3 ドメインを跨ぐ**読み取り集計**。各ドメインの既存認可を必ず通す（集計バイパス禁止）。

#### 集計内容（スコープ単位）

| 区分 | カウント | 直近アイテム | ソースドメイン |
|------|---------|------------|--------------|
| 回覧板 | 当該ユーザーの**未確認**回覧件数 | 直近 3 件（title / circulatedAt / deadline）| circulation |
| アンケート | 当該ユーザーの**未回答**アンケート件数 | 直近 3 件（title / deadline）| survey |
| 出席確認 | 当該ユーザーの**未回答出欠**件数（直近イベント）| 直近 3 件（eventTitle / startsAt）| schedule（schedule_attendances）|

#### レスポンス例（`teamActionRequired` / action-required EP 共通形）

```json
{
  "data": {
    "circulation": {
      "unconfirmed_count": 2,
      "items": [
        { "id": "uuid-...", "title": "5月度 回覧", "circulated_at": "2026-05-28T09:00:00+09:00", "deadline": "2026-06-02T23:59:59+09:00" }
      ]
    },
    "survey": {
      "unanswered_count": 1,
      "items": [
        { "id": 88, "title": "懇親会の出欠アンケート", "deadline": "2026-06-01T23:59:59+09:00" }
      ]
    },
    "attendance": {
      "unanswered_count": 3,
      "items": [
        { "schedule_id": 201, "event_title": "定例ミーティング", "starts_at": "2026-06-03T10:00:00+09:00" }
      ]
    },
    "total_action_count": 6
  }
}
```

#### 集計の認可・トランザクション原則

- **集約はダッシュボードのファサード**（`ScopeActionRequiredFacade` 等）で行い、各ドメインの既存 Service メソッドを呼ぶ（原則 5：`@Transactional` をドメイン跨ぎにしない。集計は読み取りのみで原則トランザクション不要）。
- 各ドメイン Service は**自身の認可（per-scope 認可）を内部で適用**する。ファサードは認可をバイパスしない。回覧板の閲覧権がないユーザーには 0 件（または当該区分を非表示）を返す。
- パフォーマンス: 3 ドメインの集計を `CompletableFuture`（Virtual Threads）で並行取得（F02.2 と同方式）。`unread_count`（§3.1 タグバッジ）は Valkey にキャッシュ（`scope:action:{userId}:{scopeType}:{scopeId}`、短 TTL）。

---

## 4. エラーコード

**ファイル**: `DashboardScopeTabErrorCode.java`
**プレフィックス**: `SCOPE_TAB_`

| コード | HTTP | Severity | メッセージ | 説明 |
|-------|------|---------|-----------|------|
| SCOPE_TAB_001 | 403 | WARN | 所属していないスコープは並べ替えできません | `orders[].scopeId` に非所属の ID が含まれる |
| SCOPE_TAB_002 | 400 | WARN | 表示順が不正です | `sortOrder` 重複・範囲外 |
| SCOPE_TAB_003 | 400 | WARN | スコープ種別が不正です | `scopeType` が TEAM/ORGANIZATION 以外 |
| SCOPE_TAB_004 | 404 | WARN | フォルダが見つかりません | `folderId` が存在しない、または自分所有でない |

> 既存共通エラー（COMMON_001 = バリデーション）は流用。

---

## 5. レートリミット

| エンドポイント | 上限 | 窓 | 根拠 |
|-------------|------|---|------|
| PUT `/dashboard/scope-tabs/order` | 30 回 | 1 分 | 並べ替え確定の連打防止 |
| GET `/dashboard/scope-tabs` | 既存 dashboard 系に準拠 | — | 読み取り。ページ送りの連打を許容 |
| GET `/dashboard/{team,organization}/{id}/action-required` | 既存 dashboard 系に準拠 | — | 遅延取得。ビューポート進入で都度呼ばれる |

> 検索フォームの遷移先（`/teams/search` 等）のレートリミット・XSS サニタイズは**既存検索 API に従う**（本機能で追加しない）。

---

## 6. 監査ログ

`AuditEventType` に以下を追加する:

| イベント定数 | トリガー | 備考 |
|------------|---------|------|
| `DASHBOARD_SCOPE_TAB_ORDER_UPDATED` | PUT `/dashboard/scope-tabs/order` 成功時 | 個人設定変更。低頻度・低リスクだが一貫性のため記録 |

> `GET` 系（タグ一覧・要対応集計）は読み取りのため監査ログ対象外（既存 dashboard GET と同様）。
