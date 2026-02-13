# 社会人サッカーチーム管理 Web アプリケーション（バックエンド）

LINEでの連絡漏れを解消し、チーム運営を効率化するための多機能Webアプリケーション。
独自のタイムライン（X風UI）を備え、メンバー間のコミュニケーションと外部への情報発信を両立する。

---

## 技術スタック

| 区分 | 技術 |
|------|------|
| 言語 | Java 21 LTS |
| フレームワーク | Spring Boot 3.3.x |
| ビルドツール | Gradle (Kotlin DSL) |
| データベース | MySQL 8.0 (utf8mb4) |
| DBマイグレーション | Flyway |
| 認証 | JWT (jjwt) + Spring Security 6 |
| APIドキュメント | Springdoc OpenAPI (Swagger UI) |
| マッピング | MapStruct |
| その他 | Lombok, WebSocket (STOMP) |

## アプリケーション形態

- **SPA対応**: フロントエンド（Vue 3 + Vite + Pinia）と完全分離。バックエンドはJSON APIのみ提供
- **スマホ対応**: PWA対応、画像アップロード最適化、チャンクアップロード、WebSocketリアルタイム通知
- **フロントエンドは別リポジトリ**で管理

---

## ユーザー権限

| ロール | アクセス範囲 |
|--------|-------------|
| 管理者 (ADMIN) | 全機能（メンバー管理、備品管理、広告設定、投稿編集、ロール管理） |
| チームメンバー (MEMBER) | 出欠回答、限定ブログ閲覧、写真館、タイムライン投稿、掲示板 |
| サポーター (SUPPORTER) | サポーター限定コンテンツ、試合速報 |
| ゲスト (GUEST) | 試合日程・結果、公式ブログ、SNSフィード |

※ ロール・パーミッションは管理者画面から自由に追加・編集・削除可能

---

## 主要機能

### A. マイダッシュボード（ログイン後のホーム画面）
- 各アカウントが個人ダッシュボードを持つ
- お知らせ欄（重要度付き通知）、直近イベント + 出欠状況、自分の投稿一覧、未読スレッド、最近のアクティビティ

### B. タイムライン・コミュニケーション
- X（旧Twitter）風UIのチーム内限定投稿・交流
- LINE連携による自動通知（日程更新、出欠督促）
- アカウントごとのポップアップ通知（WebSocket）

### C. スケジュール・出欠管理
- カレンダー表示（練習・試合日程）
- **Googleカレンダーとの双方向同期**
- ボタン一つで出欠回答（出席・欠席・保留）、管理者による集計・CSV出力
- カレンダー作成時の出欠機能ON/OFF、締切日時設定
- 将来拡張: カスタムアンケート機能

### D. 掲示板
- **カテゴリ別**: 目的ごとにカテゴリを自由作成（連絡事項、相談、雑談等）
- **ネスト返信（ツリー構造）**: 特定の発言に返信すると、その下に深くなっていく（LINEのように流れない）
- **重要度5段階**: CRITICAL → IMPORTANT → WARNING → INFO → LOW
  - 重要度に応じてお知らせ欄・LINE・ポップアップへ自動通知
- **既読機能（投稿時に選択可）**:
  - 数字のみ: 「既読 15」のように人数表示
  - 閲覧者表示: アイコンが並び、クリックでアカウント名表示
- ピン留め・ロック機能

### E. コンテンツ管理 (CMS)
- **ブログ**: 「外部公開用」と「メンバー限定用」の出し分け
- **試合結果**: スコア、得点者、戦評の記録
- **写真館**: メンバーのみが閲覧・アップロードできるギャラリー
- **広告枠**: Amazonアフィリエイト広告の埋め込み
- **スポンサー**: バナー表示（GOLD/SILVER/BRONZE ティア）

### F. チーム紹介
- **メインページ** + 月/年ごとの詳細ページ（階層構造）
- メインページから各詳細ページへ遷移可能
- **選手一覧ページ**（年度ごと作成）: 画像、名前、一言、拡張フィールド対応
- 管理者が「作成ボタン」でプロフィール項目を自由に追加可能

### G. 運営ツール
- **備品管理**: ボール、ビブス等の在庫管理 +「誰が持っているか」のステータス管理

### H. 管理者ダッシュボード
- 全管理機能を `/admin` 配下に集約
- ユーザー管理、ロール・パーミッション管理、スケジュール管理、ブログ管理、掲示板カテゴリ管理、備品管理、チーム紹介管理、広告・スポンサー管理、Googleカレンダー設定、LINE設定、SNS設定
- ロール・パーミッションの動的管理（チェックボックスで権限ON/OFF）

### I. 外部連携
- LINE Messaging API（通知、アカウント連携）
- Googleカレンダー（双方向同期）
- Instagram / X API（SNSフィードキャッシュ）
- Amazonアソシエイト

---

## DB設計（全36テーブル）

### 認証・権限 (6テーブル)
`users`, `roles`, `user_roles`, `permissions`, `role_permissions`, `refresh_tokens`

### タイムライン・通知 (4テーブル)
`timeline_posts`, `timeline_post_attachments`, `timeline_post_reactions`, `notifications`

### スケジュール・出欠 (4テーブル)
`schedule_events`, `attendance_responses`, `event_surveys`, `event_survey_responses`

### コンテンツ管理 (9テーブル)
`blog_posts`, `blog_tags`, `blog_post_tags`, `match_results`, `match_scorers`, `bulletin_categories`, `bulletin_threads`, `bulletin_replies`, `bulletin_read_status`

### ギャラリー (2テーブル)
`photo_albums`, `photos`

### 運営ツール (2テーブル)
`equipment_items`, `equipment_assignments`

### チーム紹介 (4テーブル)
`team_pages`, `team_page_sections`, `player_profiles`, `player_profile_fields`

### 外部連携・広告 (5テーブル)
`line_integration_config`, `google_calendar_sync`, `sns_feed_cache`, `ad_slots`, `sponsors`

---

## API設計

全APIプレフィックス: `/api/v1`

### 認証
| Method | Path | 説明 |
|--------|------|------|
| POST | `/auth/register` | ユーザー登録 |
| POST | `/auth/login` | ログイン |
| POST | `/auth/refresh` | トークンリフレッシュ |
| POST | `/auth/logout` | ログアウト |
| GET | `/auth/me` | プロフィール取得 |

### マイダッシュボード
| Method | Path | 説明 |
|--------|------|------|
| GET | `/dashboard` | ダッシュボード一括取得 |
| GET | `/dashboard/notices` | お知らせ欄 |
| GET | `/dashboard/my-posts` | 自分の投稿一覧 |
| GET | `/dashboard/upcoming-events` | 直近イベント + 出欠状況 |
| GET | `/dashboard/unread-threads` | 未読スレッド |
| GET | `/dashboard/activity` | 最近のアクティビティ |

### タイムライン
`GET/POST /timeline`, `GET/PUT/DELETE /timeline/{id}`, `POST /timeline/{id}/replies`, `POST/DELETE /timeline/{id}/reactions`, `PATCH /timeline/{id}/pin`

### スケジュール・出欠
`GET/POST /schedules`, `GET/PUT/DELETE /schedules/{id}`, `POST /schedules/{id}/attendance`, `GET /schedules/{id}/attendance`, `GET /schedules/{id}/attendance/export`, `GET /schedules/calendar`

### ブログ
`GET/POST /blog/posts`, `GET /blog/posts/{slug}`, `PUT/DELETE /blog/posts/{id}`, `PATCH /blog/posts/{id}/publish`

### 試合結果
`GET/POST /matches`, `GET/PUT/DELETE /matches/{id}`, `POST/DELETE /matches/{id}/scorers`

### ギャラリー
`GET/POST /gallery/albums`, `GET/PUT/DELETE /gallery/albums/{id}`, `POST /gallery/albums/{id}/photos`, `DELETE /gallery/photos/{id}`

### 備品管理
`GET/POST /equipment`, `GET/PUT/DELETE /equipment/{id}`, `POST /equipment/{id}/assign`, `PATCH /equipment/{id}/return`

### 掲示板
`GET/POST /bulletin/categories`, `PUT/DELETE /bulletin/categories/{id}`, `GET/POST /bulletin/threads`, `GET/PUT/DELETE /bulletin/threads/{id}`, `PATCH /bulletin/threads/{id}/priority`, `POST /bulletin/threads/{id}/read`, `GET /bulletin/threads/{id}/readers`, `PATCH /bulletin/threads/{id}/pin`, `PATCH /bulletin/threads/{id}/lock`, `POST /bulletin/threads/{id}/replies`, `POST /bulletin/replies/{id}/replies`, `PUT/DELETE /bulletin/replies/{id}`

### 通知
`GET /notifications`, `GET /notifications/unread-count`, `PATCH /notifications/{id}/read`, `PATCH /notifications/read-all`, `WS /ws`

### チーム紹介
`GET/POST /team/pages`, `GET/PUT/DELETE /team/pages/{id}`, `PATCH /team/pages/{id}/publish`, `GET/POST /team/pages/{id}/sections`, `PUT/DELETE /team/sections/{id}`, `GET/POST /team/players`, `GET/PUT/DELETE /team/players/{id}`, `POST /team/players/bulk`, `GET/POST /team/player-fields`, `PUT/DELETE /team/player-fields/{id}`

### 管理者ダッシュボード
`GET /admin/dashboard`, `/admin/users/**`, `/admin/roles/**`, `/admin/permissions/**`, `/admin/schedules/**`, `/admin/blog/**`, `/admin/bulletin/categories/**`, `/admin/equipment/**`, `/admin/team/**`, `/admin/ads/**`, `/admin/sponsors/**`, `/admin/google-calendar/**`, `/admin/line/**`, `/admin/sns/**`

### Googleカレンダー連携
`GET /admin/google-calendar/status`, `POST /admin/google-calendar/connect`, `GET /admin/google-calendar/callback`, `DELETE /admin/google-calendar/disconnect`, `POST /admin/google-calendar/sync`, `PUT /admin/google-calendar/settings`, `GET /admin/google-calendar/preview`

### LINE連携
`POST /line/webhook`, `POST/DELETE /line/link`

### 広告・スポンサー
`GET /ads`, `GET /sponsors`, `CRUD /admin/ads`, `CRUD /admin/sponsors`

---

## 実装フェーズ

| Phase | 内容 | 成果物 |
|-------|------|--------|
| **1** | プロジェクト基盤 + 認証 + ユーザー管理 + 管理者ダッシュボード基盤 + 全DB設計(Flyway) | Auth API + 管理者API + Swagger UI |
| **2** | スケジュール・出欠管理 + Googleカレンダー同期 | カレンダー + 出欠API + Google連携 |
| **3** | タイムライン・通知 | X風フィード + WebSocket通知 |
| **4** | ブログ・試合結果・掲示板・チーム紹介 | CMS API群 |
| **5** | ギャラリー・備品管理 | ファイルアップロード + 備品API |
| **6** | LINE連携・SNS・広告 | 外部連携API群 |
| **7** | ポリッシュ・テスト・Docker化 | 本番デプロイ可能 |

---

## インフラ構成

- **初期**: AWS EC2 + MySQL
- **将来**: ロリポップ等のレンタルサーバーへの移行も考慮
- ファイルストレージ抽象化（Local / S3 切替可能）
- Spring Boot fat JAR（Java 21 があればどこでも動作）
- 全シークレットは環境変数管理
- WAR パッケージングへの変更も容易

---

## Git運用ルール

- ファイルを修正するたびに `git commit` を実行する
- コミットメッセージには **変更内容の要約を日本語で記載** する
- フォーマット例: `機能追加: ユーザー認証APIの実装`、`修正: 出欠回答のバリデーション追加`
