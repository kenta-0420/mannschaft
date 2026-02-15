# Mannschaft — 汎用組織管理プラットフォーム（バックエンド）

あらゆる組織・チーム・個人をシームレスに管理するWebアプリケーション。
モジュール式テンプレートにより、スポーツチーム、整骨院、学校、会社、飲食店、美容室、ジム、町内会など業種を問わず柔軟に対応する。
独自のタイムライン（X風UI）やSlack風チャットを備え、メンバー間のコミュニケーションと外部への情報発信を両立する。

---

## 想定ユースケース

スポーツチーム / 整骨院・治療院 / 学校（クラス連絡・学年連絡・同窓会） / 会社 / 飲食店・店舗 / 美容室・サロン / コミュニティ・サークル / 医療・福祉（病院・介護施設・薬局） / ジム・フィットネス / 宗教・町内会 / その他（カスタムテンプレートで対応）

---

## 技術スタック

### バックエンド

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
| リアルタイム通信 | WebSocket (STOMP), Redis (メッセージブローカー) |
| キャッシュ/セッション | Redis (トークン無効化管理) |
| テスト | Testcontainers (MySQL 8.0) |
| その他 | Lombok, Virtual Threads (Project Loom) |

### フロントエンド（別リポジトリ）

| 区分 | 技術 |
|------|------|
| フレームワーク | Nuxt.js 3 (Vue 3 ベース) |
| レンダリング | SSR + SPA ハイブリッド |
| 状態管理 | Pinia |
| SSR対象 | SEOが必要な公開ページ（ブログ、チーム紹介、活動記録等） |
| SPA対象 | ログイン後のダッシュボード、チャット等のインタラクティブ画面 |

## アプリケーション形態

- **SSR/SPA ハイブリッド**: Nuxt.js 3 によるサーバーサイドレンダリングとSPAの両立。バックエンドはJSON APIのみ提供
- **スマホ対応**: PWA対応、画像アップロード最適化、チャンクアップロード、WebSocketリアルタイム通知
- **フロントエンドは別リポジトリ**で管理

---

## アカウント構造

### 3層構造

| 層 | 説明 | 例 |
|----|------|----|
| **個人アカウント** | ユーザー単位。実名登録＋表示用ニックネーム。複数チーム/組織にマルチ所属可能。QR会員証を保有 | 田中太郎 |
| **チームアカウント** | チーム/店舗/教室等の運用単位。都道府県・市区町村を設定し地域検索可能。テンプレート選択で機能モジュールを決定 | FCマンシャフト / 田中整骨院 / 3年B組 |
| **組織アカウント** | 複数チームを束ねる上位組織。組織内に別の組織を登録する階層構造も可能 | 〇〇サッカー協会 / 〇〇高等学校 / 〇〇株式会社 |

### グループ階層

組織内にサブグループを自由に作成可能。グループ単位での連絡・スケジュール・チャット配信に対応。

例: 学校（組織）→ 学年（グループ）→ クラス（サブグループ）→ 同窓会（派生グループ）

### 紐付けと所属

- **招待URL連携**: チーム発行のURLから個人が紐付けされ、スムーズにメンバー加入
- **マルチ所属**: 1つの個人アカウントで複数のチームや組織に同時に所属可能
- **サポーター枠**: 招待不要で、外部から特定のチームを支援・フォローできる独立した枠組み

### ロール・パーミッション

| ロール | スコープ | アクセス範囲 |
|--------|----------|-------------|
| システム管理者 (SYSTEM_ADMIN) | プラットフォーム全体 | 全組織・チームの管理、テンプレート管理、システム設定、ユーザーBAN |
| 管理者 (ADMIN) | チーム/組織内 | 全機能（メンバー管理、備品管理、広告設定、投稿編集、ロール管理、モジュール設定） |
| メンバー (MEMBER) | チーム/組織内 | 出欠回答、限定コンテンツ閲覧、ギャラリー、タイムライン投稿、掲示板、チャット |
| サポーター (SUPPORTER) | チーム/組織内 | サポーター限定コンテンツ、活動速報 |
| ゲスト (GUEST) | チーム/組織内 | スケジュール・活動記録、公式ブログ、SNSフィード |

※ ロール・パーミッションは管理者画面から自由に追加・編集・削除可能
※ グループ単位でもロール設定可能

---

## モジュール式テンプレートシステム

チーム作成時にテンプレートを選択すると、有効な機能モジュールが自動設定される。管理者は後からモジュールのON/OFFを変更可能。

### テンプレートの特徴

- **属性テンプレート**: 業種に応じた最適な機能モジュールと情報入力項目をプリセット
- **カスタム作成**: モジュール自体の項目変更や、完全新規モジュールの作成が可能
- **共有マーケット**: カスタムモジュール・テンプレートを他ユーザーへ公開・共有
- **権限と公開設定**: 組織内でのチーム情報の開示レベル（組織内のみ、全公開、非公開）を各チームの裁量で設定可能
- 想定外のユースケースにもユーザー自身で対応可能な設計

### コア機能モジュール（全テンプレート共通）

QR会員証 / ダッシュボード / タイムライン / CMS（ブログ・お知らせ） / チャット / 会費・決済 / プッシュ通知 / アンケート・投票 / ファイル共有

### 選択式機能モジュール

スケジュール・出欠管理 / 予約管理 / サービス履歴 / 活動記録 / 掲示板 / マッチング・対外交流 / パフォーマンス管理 / メンバー紹介 / 備品管理 / ギャラリー

### テンプレート × モジュール対応表（プリセット例）

| モジュール | スポーツ | 整骨院 | 学校 | 会社 | 飲食店 | 美容室 | ジム | サークル | 町内会 |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| スケジュール・出欠 | ○ | - | ○ | ○ | - | - | ○ | ○ | ○ |
| 予約管理 | - | ○ | - | - | ○ | ○ | ○ | - | - |
| サービス履歴 | - | ○ | - | - | - | ○ | ○ | - | - |
| 活動記録 | ○ | - | ○ | ○ | - | - | ○ | ○ | ○ |
| 掲示板 | ○ | - | ○ | ○ | - | - | - | ○ | ○ |
| マッチング | ○ | - | - | - | - | - | - | ○ | - |
| パフォーマンス管理 | ○ | - | ○ | - | - | - | ○ | - | - |
| メンバー紹介 | ○ | - | ○ | ○ | - | - | - | ○ | ○ |
| 備品管理 | ○ | ○ | ○ | ○ | ○ | ○ | ○ | - | ○ |
| ギャラリー | ○ | - | ○ | ○ | ○ | ○ | - | ○ | ○ |

※ 上記はプリセットの初期値。管理者が自由にON/OFF変更可能

---

## 主要機能

### コア機能（全テンプレート共通）

#### A. QR会員証
- メンバーごとに一意のIDを発行し、QRコードを生成
- スマホ画面での表示に最適化
- 来店・受付時のスキャンによる本人確認

#### B. マイダッシュボード（ログイン後のホーム画面）
- 各アカウントが個人ダッシュボードを持つ
- お知らせ欄（重要度付き通知）、直近イベント + 出欠状況、自分の投稿一覧、未読スレッド、最近のアクティビティ
- **UXカスタマイズ**: チェックリスト形式のトグルスイッチで、ウィジェットの表示/非表示を自由に切り替え可能
- 所属するチーム/組織ごとのパフォーマンスサマリー表示
- 個人カレンダー搭載（**Googleカレンダーとの同期**対応）

#### C. タイムライン・コミュニケーション
- X（旧Twitter）風UIのチーム内限定投稿・交流
- LINE連携による自動通知（日程更新、出欠督促）
- アカウントごとのポップアップ通知（WebSocket）

#### D. コンテンツ管理 (CMS)
- **ブログ**: 「外部公開用」と「メンバー限定用」の出し分け
- **お知らせ**: 重要度付き通知配信
- **写真館**: メンバーのみが閲覧・アップロードできるギャラリー
- **広告枠**: Amazonアフィリエイト広告の埋め込み
- **スポンサー**: バナー表示（GOLD/SILVER/BRONZE ティア）

#### E. チャット
- **階層化スレッド**: 特定のコメントに対して返答を階層化（Slack/Teamsスタイル）。情報の埋没を防止
- **絵文字リアクション**: メッセージへの絵文字のみの返答（Slack/Teams風）
- **グループ管理**: 複数チーム間、またはチーム代表者・管理者限定のチャットルーム作成
- **チーム間メッセージング**: 組織を横断したコミュニケーション

#### F. 会費・決済
- 月会費・年会費・都度払いの設定
- 集金管理・支払い状況の一覧表示
- 組織やチームに対する会費の支払い・集金機能

#### G. プッシュ通知
- 予約リマインド（予約時間が近づいたら自動通知）
- 出欠督促、お知らせ配信
- LINE連携による自動通知
- WebSocketによるリアルタイムポップアップ通知
- 重要度に応じた通知チャネル自動選択

#### H. アンケート・投票
- カスタム設問作成（単一選択・複数選択・自由記述等）
- 匿名/記名の切替
- 集計結果のグラフ表示
- 回答期限の設定

#### I. ファイル共有
- ドキュメント・資料の共有ストレージ
- フォルダ管理（階層構造）
- アクセス権限設定（チーム内/グループ内/個別指定）

### 選択式機能モジュール

#### J. スケジュール・出欠管理
- カレンダー表示（イベント日程）
- **Googleカレンダーとの双方向同期**
- ボタン一つで出欠回答（出席・欠席・保留）、管理者による集計・CSV出力
- カレンダー作成時の出欠機能ON/OFF、締切日時設定

#### K. 予約管理
- 時間枠ベースの予約・キャンセル
- スタッフ別の予約枠管理
- 予約時間が近づいたらプッシュ通知でリマインド
- 予約履歴の管理

#### L. サービス履歴
- 施術記録・来店記録・対応履歴等の汎用記録システム
- チームごとに保存したい項目を自由に設定（カスタムフィールド対応）
- メンバー個人のダッシュボードへ自動反映

#### M. 活動記録
- 活動内容の記録（スコア、参加者、評価等）
- カスタムフィールドで業種に応じた記録項目を自由定義
- 統計・集計機能

#### N. 掲示板
- **カテゴリ別**: 目的ごとにカテゴリを自由作成（連絡事項、相談、雑談等）
- **ネスト返信（ツリー構造）**: 特定の発言に返信すると、その下に深くなっていく（LINEのように流れない）
- **重要度5段階**: CRITICAL → IMPORTANT → WARNING → INFO → LOW
  - 重要度に応じてお知らせ欄・LINE・ポップアップへ自動通知
- **既読機能（投稿時に選択可）**:
  - 数字のみ: 「既読 15」のように人数表示
  - 閲覧者表示: アイコンが並び、クリックでアカウント名表示
- ピン留め・ロック機能

#### O. マッチング・対外交流
- 地域やレベルに応じた対戦相手・交流先の募集・応募
- マッチング成立時、**双方のスケジュールへ自動反映**
- **NGチーム設定**: トラブル防止のため、特定の相手をブロック・非表示にする機能

#### P. パフォーマンス管理
- カスタムデータ記録（得点、出場時間、走行距離等を自由に定義）
- チームで記録されたデータが個人の専用ダッシュボードへ自動反映
- 個人・チーム統計ダッシュボード

#### Q. メンバー紹介
- **メインページ** + 月/年ごとの詳細ページ（階層構造）
- メインページから各詳細ページへ遷移可能
- **メンバー一覧ページ**（年度ごと作成）: 画像、名前、一言、拡張フィールド対応
- 管理者が「作成ボタン」でプロフィール項目を自由に追加可能

#### R. 備品管理
- 備品・在庫の管理 +「誰が持っているか」のステータス管理

### 管理者ダッシュボード

#### S. 管理者ダッシュボード
- 全管理機能を `/admin` 配下に集約
- ユーザー管理、ロール・パーミッション管理、モジュール設定、スケジュール管理、ブログ管理、掲示板カテゴリ管理、備品管理、メンバー紹介管理、広告・スポンサー管理、予約管理設定、Googleカレンダー設定、LINE設定、SNS設定
- ロール・パーミッションの動的管理（チェックボックスで権限ON/OFF）
- テンプレート・モジュールのON/OFF管理

#### T. システム管理者ダッシュボード
- プラットフォーム全体の管理を `/system-admin` 配下に集約
- 全組織・チームの一覧・管理
- テンプレート・モジュールのマスター管理
- ユーザーBAN・通報管理
- システム設定・メンテナンス
- テンプレート共有マーケットの管理

### 外部連携

#### U. 外部連携
- LINE Messaging API（通知、アカウント連携）
- Googleカレンダー（双方向同期）
- Instagram / X API（SNSフィードキャッシュ）
- Amazonアソシエイト

---

## DB設計

### 認証・権限 (7テーブル)
`users`, `roles`, `user_roles`, `permissions`, `role_permissions`, `refresh_tokens`, `system_admins`

### 組織・マルチ所属 (4テーブル)
`organizations`, `organization_members`, `team_memberships`, `invitation_links`

### グループ階層 (3テーブル)
`groups`, `group_members`, `group_hierarchy`

### テンプレート・モジュール (6テーブル)
`team_templates`, `template_modules`, `template_fields`, `module_definitions`, `module_field_definitions`, `template_marketplace`

### QR会員証 (1テーブル)
`member_cards`

### タイムライン・通知 (4テーブル)
`timeline_posts`, `timeline_post_attachments`, `timeline_post_reactions`, `notifications`

### チャット (4テーブル)
`chat_channels`, `chat_messages`, `chat_channel_members`, `chat_message_reactions`

### スケジュール・出欠 (4テーブル)
`schedule_events`, `attendance_responses`, `event_surveys`, `event_survey_responses`

### 予約管理 (3テーブル)
`reservation_slots`, `reservations`, `reservation_reminders`

### サービス履歴 (2テーブル)
`service_records`, `service_record_fields`

### コンテンツ管理 (9テーブル)
`blog_posts`, `blog_tags`, `blog_post_tags`, `activity_results`, `activity_participants`, `bulletin_categories`, `bulletin_threads`, `bulletin_replies`, `bulletin_read_status`

### ギャラリー (2テーブル)
`photo_albums`, `photos`

### アンケート・投票 (4テーブル)
`surveys`, `survey_questions`, `survey_options`, `survey_responses`

### ファイル共有 (3テーブル)
`shared_files`, `shared_folders`, `file_permissions`

### 運営ツール (2テーブル)
`equipment_items`, `equipment_assignments`

### メンバー紹介 (4テーブル)
`team_pages`, `team_page_sections`, `member_profiles`, `member_profile_fields`

### マッチング (4テーブル)
`match_requests`, `match_proposals`, `match_reviews`, `ng_teams`

### パフォーマンス管理 (2テーブル)
`performance_metrics`, `performance_records`

### 決済・会費 (3テーブル)
`payment_plans`, `payment_transactions`, `membership_fees`

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

### 組織管理
`CRUD /organizations`, `GET /organizations/{id}/teams`, `POST /organizations/{id}/invite`, `POST /invitations/{token}/accept`, `GET /users/{id}/teams`, `GET /users/{id}/organizations`

### グループ管理
`CRUD /groups`, `GET /groups/{id}/members`, `POST /groups/{id}/members`, `DELETE /groups/{id}/members/{userId}`, `GET /organizations/{id}/groups`

### テンプレート・モジュール
`GET /templates`, `GET /templates/{id}`, `POST /templates`, `PUT /templates/{id}`, `GET /templates/{id}/modules`, `PUT /templates/{id}/modules`, `GET /modules`, `POST /modules`, `PUT /modules/{id}`, `POST /templates/marketplace/publish`, `GET /templates/marketplace`

### QR会員証
`GET /members/{id}/card`, `POST /members/{id}/card/generate`, `GET /members/card/verify/{code}`

### マイダッシュボード
| Method | Path | 説明 |
|--------|------|------|
| GET | `/dashboard` | ダッシュボード一括取得 |
| GET | `/dashboard/notices` | お知らせ欄 |
| GET | `/dashboard/my-posts` | 自分の投稿一覧 |
| GET | `/dashboard/upcoming-events` | 直近イベント + 出欠状況 |
| GET | `/dashboard/unread-threads` | 未読スレッド |
| GET | `/dashboard/activity` | 最近のアクティビティ |
| PUT | `/dashboard/widgets` | ウィジェット表示設定 |

### タイムライン
`GET/POST /timeline`, `GET/PUT/DELETE /timeline/{id}`, `POST /timeline/{id}/replies`, `POST/DELETE /timeline/{id}/reactions`, `PATCH /timeline/{id}/pin`

### チャット
`CRUD /chat/channels`, `GET /chat/channels/{id}/messages`, `POST /chat/channels/{id}/messages`, `PUT/DELETE /chat/messages/{id}`, `POST /chat/messages/{id}/reactions`, `DELETE /chat/messages/{id}/reactions/{emoji}`, `WS /ws/chat`

### スケジュール・出欠
`GET/POST /schedules`, `GET/PUT/DELETE /schedules/{id}`, `POST /schedules/{id}/attendance`, `GET /schedules/{id}/attendance`, `GET /schedules/{id}/attendance/export`, `GET /schedules/calendar`

### 予約管理
`GET/POST /reservations`, `GET/PUT/DELETE /reservations/{id}`, `GET /reservation-slots`, `POST /reservation-slots`, `PUT/DELETE /reservation-slots/{id}`, `GET /reservations/upcoming`

### サービス履歴
`GET/POST /service-records`, `GET/PUT/DELETE /service-records/{id}`, `GET /members/{id}/service-history`, `CRUD /service-record-fields`

### ブログ
`GET/POST /blog/posts`, `GET /blog/posts/{slug}`, `PUT/DELETE /blog/posts/{id}`, `PATCH /blog/posts/{id}/publish`

### 活動記録
`GET/POST /activities`, `GET/PUT/DELETE /activities/{id}`, `POST/DELETE /activities/{id}/participants`

### ギャラリー
`GET/POST /gallery/albums`, `GET/PUT/DELETE /gallery/albums/{id}`, `POST /gallery/albums/{id}/photos`, `DELETE /gallery/photos/{id}`

### 備品管理
`GET/POST /equipment`, `GET/PUT/DELETE /equipment/{id}`, `POST /equipment/{id}/assign`, `PATCH /equipment/{id}/return`

### 掲示板
`GET/POST /bulletin/categories`, `PUT/DELETE /bulletin/categories/{id}`, `GET/POST /bulletin/threads`, `GET/PUT/DELETE /bulletin/threads/{id}`, `PATCH /bulletin/threads/{id}/priority`, `POST /bulletin/threads/{id}/read`, `GET /bulletin/threads/{id}/readers`, `PATCH /bulletin/threads/{id}/pin`, `PATCH /bulletin/threads/{id}/lock`, `POST /bulletin/threads/{id}/replies`, `POST /bulletin/replies/{id}/replies`, `PUT/DELETE /bulletin/replies/{id}`

### アンケート・投票
`GET/POST /surveys`, `GET/PUT/DELETE /surveys/{id}`, `POST /surveys/{id}/responses`, `GET /surveys/{id}/results`

### ファイル共有
`GET/POST /files`, `GET/DELETE /files/{id}`, `GET /files/{id}/download`, `CRUD /folders`, `PUT /files/{id}/permissions`

### マッチング
`GET/POST /matching/requests`, `GET/PUT/DELETE /matching/requests/{id}`, `POST /matching/requests/{id}/propose`, `PATCH /matching/proposals/{id}/accept`, `PATCH /matching/proposals/{id}/reject`, `POST /matching/reviews`, `CRUD /matching/ng-teams`

### パフォーマンス
`CRUD /performance/metrics`, `POST /performance/records`, `GET /performance/stats`, `GET /members/{id}/performance`

### 決済・会費
`POST /payments/plans`, `GET /payments/plans`, `POST /payments/charge`, `GET /payments/history`, `POST /membership-fees/collect`, `GET /membership-fees/status`

### 通知
`GET /notifications`, `GET /notifications/unread-count`, `PATCH /notifications/{id}/read`, `PATCH /notifications/read-all`, `WS /ws`

### メンバー紹介
`GET/POST /team/pages`, `GET/PUT/DELETE /team/pages/{id}`, `PATCH /team/pages/{id}/publish`, `GET/POST /team/pages/{id}/sections`, `PUT/DELETE /team/sections/{id}`, `GET/POST /team/members`, `GET/PUT/DELETE /team/members/{id}`, `POST /team/members/bulk`, `GET/POST /team/member-fields`, `PUT/DELETE /team/member-fields/{id}`

### 管理者ダッシュボード
`GET /admin/dashboard`, `/admin/users/**`, `/admin/roles/**`, `/admin/permissions/**`, `/admin/modules/**`, `/admin/schedules/**`, `/admin/blog/**`, `/admin/bulletin/categories/**`, `/admin/equipment/**`, `/admin/team/**`, `/admin/ads/**`, `/admin/sponsors/**`, `/admin/reservations/**`, `/admin/google-calendar/**`, `/admin/line/**`, `/admin/sns/**`

### システム管理者
`GET /system-admin/dashboard`, `/system-admin/organizations/**`, `/system-admin/teams/**`, `/system-admin/users/**`, `/system-admin/templates/**`, `/system-admin/modules/**`, `/system-admin/marketplace/**`, `/system-admin/reports/**`, `/system-admin/settings/**`

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
| **1** | プロジェクト基盤 + 3層アカウント構造 + 認証 + ロール・パーミッション + テンプレート・モジュール基盤 + 全DB設計(Flyway) | Auth API + 組織API + テンプレートAPI + Swagger UI |
| **2** | QR会員証 + マイダッシュボード + ダッシュボードUXカスタマイズ | QR API + ダッシュボードAPI |
| **3** | スケジュール・出欠管理 + 予約管理 + Googleカレンダー同期 | カレンダー + 出欠API + 予約API + Google連携 |
| **4** | タイムライン + チャット（階層スレッド・絵文字リアクション）+ プッシュ通知 | X風フィード + チャットAPI + WebSocket通知 |
| **5** | 掲示板 + アンケート・投票 + ファイル共有 | 掲示板API + アンケートAPI + ファイルAPI |
| **6** | CMS（ブログ・活動記録）+ メンバー紹介 + ギャラリー | CMS API群 |
| **7** | サービス履歴 + パフォーマンス管理 + 備品管理 | サービス履歴API + パフォーマンスAPI + 備品API |
| **8** | マッチング・対外交流 + 会費・決済 | マッチングAPI + 決済API |
| **9** | LINE連携・SNS・広告・スポンサー | 外部連携API群 |
| **10** | 管理者ダッシュボード + システム管理者ダッシュボード + テンプレート共有マーケット | 管理画面 + マーケット |
| **11** | ポリッシュ・テスト・Docker化 | 本番デプロイ可能 |

---

## インフラ構成

- **初期**: AWS EC2 + MySQL + Redis
- **将来**: ロリポップ等のレンタルサーバーへの移行も考慮
- ファイルストレージ: S3互換オブジェクトストレージ（Local / S3 切替可能な抽象化設計）
- Spring Boot fat JAR（Java 21 があればどこでも動作）
- 全シークレットは環境変数管理
- WAR パッケージングへの変更も容易
- 決済機能導入時にはセキュリティ要件の追加検討

---

## アーキテクチャ・開発ガイドライン

### ストレージ戦略
- 画像および大容量ファイルは外部オブジェクトストレージ（S3互換）へ保存
- クライアントからのアップロードは、バックエンドが発行する**署名付きURL（Pre-signed URL）**を利用してストレージへ直接送信する設計
- サーバーを経由しないため、アップロード時のサーバー負荷を軽減

### スケーラビリティと通信
- WebSocket (STOMP) のメッセージブローカーとして **Redis** を利用（将来的な複数台構成を想定）
- 認証情報の無効化（ログアウト・トークンブラックリスト）管理にも Redis を活用
- Java 21 の **Virtual Threads** (Project Loom) を活用し、大量の同時接続を効率的に処理

### 開発・テスト環境
- データベースを用いたテストには **Testcontainers** を使用し、Dockerコンテナ上で MySQL 8.0 を立ち上げて実行
- Flyway のマイグレーションファイルは `src/main/resources/db/migration` にバージョン管理して配置
- CORS設定は開発環境と本番環境で適切に切り替えられるようプロファイル管理

### フロントエンド・バックエンド連携
- APIの型定義は **Springdoc OpenAPI** が生成する `swagger.json` を基に、フロントエンド（Nuxt 3）側で **OpenAPI Generator** 等を用いて型定義を自動生成
- バックエンドのAPI変更がフロントエンドの型に即座に反映されるワークフローを構築

### パフォーマンス最適化
- Nuxt 3 の SSR を活用し、チーム紹介などの公開ページにおける **LCP（Largest Contentful Paint）を 2.5s 以内** に抑える
- 画像は適切なフォーマット（WebP/AVIF）と遅延読み込みで最適化
- API レスポンスの適切なキャッシュ戦略（Redis + HTTP キャッシュヘッダー）

---

## Git運用ルール

- ファイルを修正するたびに `git commit` を実行する
- コミットメッセージには **変更内容の要約を日本語で記載** する
- フォーマット例: `機能追加: ユーザー認証APIの実装`、`修正: 出欠回答のバリデーション追加`
