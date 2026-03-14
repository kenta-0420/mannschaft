# DB設計 詳細注釈

README.md の DB設計セクションから分離した、各テーブルの詳細なカラム定義・制約・設計判断を記載する。

> **正（Single Source of Truth）**: 本ファイルの記述が README.md の DB 概要と矛盾する場合は本ファイルを優先する。

---

## 認証・権限

- SYSTEM_ADMIN は `roles` テーブルの1レコード + `user_roles` で割り当て。専用テーブルは設けず RBAC に統一する
- `users`: `last_name` / `first_name`（実名）・`display_name`（愛称1、表示用ニックネーム）・`nickname2`（愛称2、nullable）を持つ。電子印鑑は `last_name` を使用。検索・メンションでは実名・愛称いずれでもヒットするようにする
- `users.is_searchable BOOLEAN DEFAULT true`: OFF にすると他ユーザーの検索結果に表示されない（メンションは引き続き利用可能）
- `users.postal_code VARCHAR(10) nullable`: 郵便番号（ハイフン付き、例: 150-0001）。任意入力。プロモーション配信のセグメント条件（F09.2）で使用
- `users.address VARCHAR(500) nullable`: 住所。任意入力。将来の住民台帳連携にも使用
- `users.date_of_birth DATE nullable`: 生年月日。任意入力。プロモーション配信の年齢範囲セグメント（F09.2）で使用
- `users.gender ENUM('MALE', 'FEMALE', 'OTHER', 'PREFER_NOT_TO_SAY') nullable`: 性別。任意入力。プロモーション配信の性別セグメント（F09.2）で使用
- `users.archived_at DATETIME nullable`: 最終ログインから6ヶ月経過時にバッチ処理で付与。null = アクティブ。ログイン成功時にクリアする。アーカイブ中はリスト系クエリから除外し、Redis キャッシュも保持しない
- `role_permissions`: プラットフォームレベルの権限デフォルト（SYSTEM_ADMIN が管理）。コンテンツ削除系パーミッション（`CONTENT_DELETE` 等）は DEPUTY_ADMIN のデフォルトを `false` に設定する
- `team_role_permissions`: チーム/組織レベルの権限カスタマイズ（`scope_type`: TEAM/ORGANIZATION, `scope_id`, `role_id`, `permission_id`, `is_enabled`）。主に MEMBER のデフォルト調整に使用
- `permission_groups`: ADMIN が作成する名前付き権限グループ（`scope_type`: TEAM/ORGANIZATION, `scope_id`, `name`, `description` nullable, `created_by`）。テンプレートとして保存・複製可能
- `permission_group_permissions`: 各権限グループに含まれるパーミッション（`group_id`, `permission_id`, `is_enabled`）。パーミッションを個別に ON/OFF する
- `user_permission_groups`: ユーザーと権限グループの多対多中間テーブル（`user_role_id` FK → `user_roles`, `permission_group_id` FK → `permission_groups`）。1ユーザーに複数グループを割り当て可能
- `user_roles`: `team_id` / `organization_id` のスコープカラムを持ち、マルチ所属・複数 DEPUTY_ADMIN に対応。権限グループの割り当ては `user_permission_groups` で管理する
- `two_factor_auth`: TOTP シークレット・有効フラグを保持。バックアップコード（8桁 × 8件）をハッシュ化して別カラムに保存し、デバイス紛失時の緊急復旧に対応する
- `password_reset_tokens`: トークンは `SecureRandom` + Base64URL 方式で生成。有効期限は発行から **30分** とし、使用済みトークンは即時無効化する

## チーム管理

- チームと組織の紐付けは `team_org_memberships` テーブルで管理する多対多関係。1つのチームが複数組織に所属可能
- `member_count` カラムを denormalize で保持し、メンバー追加・削除時にアトミック更新する（COUNT クエリ廃止）
- `name`（正式名称）・`nickname1`・`nickname2`（愛称、両方 nullable）を持つ。検索・表示では正式名称と愛称いずれでもヒットするようにする
- `is_searchable BOOLEAN DEFAULT true`: OFF にすると検索結果に表示されない（招待URLのみで参加できる非公開チーム等に対応）
- `archived_at DATETIME nullable`: 全メンバーの最終ログインのうち最新が12ヶ月経過した時点でバッチ処理が付与。null = アクティブ。いずれかのメンバーがログインすると即時クリア

## 組織・マルチ所属

- `organizations`: `name`（正式名称）・`nickname1`・`nickname2`（愛称、両方 nullable）を持つ。検索・表示では正式名称と愛称いずれでもヒットするようにする
- `organizations.org_type` ENUM: NONPROFIT/FORPROFIT。組織作成時に選択。組織数課金の無料枠・単価の適用区分に使用する。ADMIN が管理画面から自己申告で変更可能（承認不要）
- `organizations.is_searchable BOOLEAN DEFAULT true`: OFF にすると検索結果に表示されない
- `organizations.archived_at DATETIME nullable`: 傘下の全チーム・全メンバーの最終ログインのうち最新が12ヶ月経過した時点でバッチ処理が付与。いずれかのメンバーがログインすると即時クリア
- `team_org_memberships`: チームと組織の多対多中間テーブル（`team_id`, `organization_id`, `joined_at`）。V2.023 で `teams.organization_id` を DROP し本テーブルへ移行
- `invite_tokens`: `invite_type`（ENUM: `EMAIL` / `URL` / `QR`）・`token`（`SecureRandom` + Base64URL 方式で生成する暗号論的乱数トークン）・`expires_at`（nullable、null=無期限）・`max_uses`（nullable、null=無制限）・`used_count`・`is_active`（手動無効化フラグ）を持つ。QR コードはトークンから動的生成し画像は保存しない。期限切れ・上限到達・`is_active=false` のいずれかで即時失効

## グループ階層

- `group_hierarchy`: 隣接リスト（`parent_group_id`）＋クロージャテーブル方式で実装。深い階層の一括取得には MySQL 8.0 の再帰的 CTE（`WITH RECURSIVE`）を使用する

## テンプレート・モジュール

- `template_fields`: 業種テンプレートに含まれるカスタム項目の定義（テンプレートレベルの設定。運営が業種別プリセットとして管理）
- `module_field_definitions`: 各モジュールが提供する汎用フィールドの定義（モジュールレベルの設定。モジュール固有の入力項目スキーマ）

## プラン・サブスクリプション

- `subscription_plans`: プラン種別の定義（FREE / INDIVIDUAL / PACKAGE）。フリープランは選択式モジュール10個まで無料
- `module_prices`: 選択式モジュールの個別価格（`module_definition_id`, `monthly_price`, `yearly_price`, `currency`）。SYSTEM_ADMINが管理画面から設定変更。変更は翌請求サイクルから適用
- `plan_packages`: パッケージ定義（`name`, `description`, `monthly_price`, `yearly_price`, `is_active`）。SYSTEM_ADMINが管理・随時追加
- `plan_package_modules`: パッケージに含まれる選択式モジュールの中間テーブル（`package_id`, `module_definition_id`）
- `discount_campaigns`: 期間限定割引キャンペーン（`name`, `discount_type`: PERCENTAGE/FIXED_AMOUNT, `discount_value`, `start_at`, `end_at`, `target_type`: ALL/MODULE/PACKAGE, `target_id` nullable, `coupon_code` nullable, `max_uses` nullable, `used_count`）。SYSTEM_ADMINが設定
- `team_discount_usages`: チームへのキャンペーン適用履歴（重複適用防止・上限管理）
- `tax_settings`: 消費税設定（`tax_name`, `rate` DECIMAL e.g. 10.00, `is_included_in_price` BOOLEAN, `is_active`）。複数レコードで将来の複数税率に対応できる設計とする
- `team_subscriptions`: チームの現在の契約状態（`billing_cycle`: MONTHLY/YEARLY, `current_period_start`, `current_period_end`, `next_billing_date`, `status`: ACTIVE/TRIALING/PAST_DUE/CANCELED）。モジュール個別・パッケージいずれの契約も本テーブルで管理
- `subscription_invoices`: 月次/年次の請求書（税抜額・税額・税込額・適用割引・キャンペーンIDを明記）

## 組織数課金

- `org_count_billing_tiers`: 組織種別ごとの無料枠・課金単価の設定テーブル。SYSTEM_ADMINが管理画面から変更可能
  - `org_type` ENUM: NONPROFIT/FORPROFIT
  - `free_tier_count` INT（無料上限チーム数。例: NONPROFIT=20, FORPROFIT=5）
  - `price_per_unit_monthly` DECIMAL（超過1チームあたりの月額。例: FORPROFIT=200.00）
  - `currency` VARCHAR DEFAULT 'JPY'
  - `is_active` BOOLEAN
  - 各 `org_type` につき有効レコードは1件のみ。変更時は翌月請求サイクルから適用
- `org_count_invoices`: 月次の組織数課金請求書
  - `organization_id`、`billing_month` DATE（月初日）
  - `org_type` ENUM（発行時点のスナップショット）
  - `team_count` INT（集計月時点のアクティブチーム数）
  - `free_tier_count` INT（適用された無料枠数）
  - `billed_count` INT（課金対象チーム数 = team_count - free_tier_count, MIN 0）
  - `unit_price` DECIMAL、`subtotal` DECIMAL、`tax_amount` DECIMAL、`total_amount` DECIMAL
  - `status` ENUM: PENDING/PAID/FAILED
  - `billed_count = 0` の場合はレコードを作成しない（課金なし月はスキップ）
  - モジュール課金の `subscription_invoices` とは独立したテーブルだが、同一請求書PDFに合算して表示する

## アクセス解析

- `page_view_logs`: 生ログ（`target_type` ENUM: TEAM_PROFILE/BLOG_POST/ACTIVITY_RECORD 等, `target_id`, `team_id`, `viewer_type`: MEMBER/GUEST/ANONYMOUS, `viewed_at`）。保持期間 **90日** 後にバッチ物理削除。90日で数千万件規模になりうるため以下のインデックスを必須とする
  - `INDEX (team_id, viewed_at)` — チーム別期間絞り込みの基本クエリ用（最重要）
  - `INDEX (target_type, target_id, viewed_at)` — コンテンツ別ランキング集計用
  - `INDEX (viewed_at)` — 90日バッチ削除の対象抽出用
- `page_view_daily_stats`: 日次集計（`target_type`, `target_id`, `team_id`, `date` DATE, `view_count`, `member_view_count`, `guest_view_count`）。永続保持。バッチで毎日 0:00 に前日分を集計・書き込む
  - `INDEX (team_id, date)` — ダッシュボードの日別・月別グラフ用
  - `INDEX (target_type, target_id, date)` — コンテンツ別ランキング用
  - `UNIQUE (target_type, target_id, date)` — 日次集計の二重書き込み防止

## 外観設定

- `user_appearance_settings`: ユーザーの外観設定（`user_id`, `theme_mode` ENUM: LIGHT/DARK/SYSTEM, `color_preset` ENUM: DEFAULT/BLUE/GREEN/PURPLE/ORANGE）。レコードが存在しない場合はデフォルト（SYSTEM + DEFAULT）を適用
- `seasonal_themes`: SYSTEM_ADMINが設定する期間限定壁紙（`name`, `image_url`, `start_at`, `end_at` nullable, `is_active`）。有効期間中はユーザー個人の色設定より優先して全画面に適用

## ダイレクトメール配信

- `direct_mail_logs`: 送信セッション（`scope_type`: TEAM/ORGANIZATION, `scope_id`, `sender_id`, `subject`, `body`, `recipient_type` ENUM: ALL/GROUP/ROLE/SELECTED, `scheduled_at` nullable, `sent_at` nullable, `status` ENUM: DRAFT/SCHEDULED/SENT/FAILED）
- `direct_mail_recipients`: 受信者ごとの配信状況（`log_id`, `user_id`, `email_address`, `status` ENUM: PENDING/SENT/FAILED/BOUNCED, `opened_at` nullable）
- **週次送信制限**: チーム/組織単位で週3回まで。制限カウントは Redis（`dm:weekly:{scopeType}:{scopeId}:{isoYearWeek}`、TTL 8日）で管理。Phase 8 の課金導入以降に上限緩和・無制限プランを検討する

## エラーレポート

- `error_reports`: ユーザーから送信されたエラー情報（`user_id` nullable, `error_message` TEXT, `stack_trace` TEXT, `route` VARCHAR, `user_agent` VARCHAR, `app_version` VARCHAR, `additional_message` TEXT nullable, `status` ENUM: NEW/REVIEWING/RESOLVED, `created_at`）。認証不要の公開エンドポイントで受け付ける

## 広告・アフィリエイト

- `affiliate_configs`: SYSTEM_ADMINが管理するアフィリエイト設定（`provider` ENUM: AMAZON, `tag_id` VARCHAR, `placement` ENUM: SIDEBAR_RIGHT/BANNER_FOOTER, `description` VARCHAR nullable, `is_active` BOOLEAN）。初期はAmazonアソシエイトのみ対応。将来の楽天等の追加に備え `provider` ENUM で拡張できる設計とする
- `ads`（将来）: 広告クリエイティブ（`title`, `image_url` S3, `click_url`, `click_type` ENUM: INTERNAL_ORG/EXTERNAL_URL/AFFILIATE, `target_organization_id` nullable（INTERNAL_ORG時）, `is_active`）。SYSTEM_ADMINが審査・管理
- `ad_campaigns`（将来）: 広告キャンペーン（`ad_id`, `advertiser_organization_id` nullable, `start_at`, `end_at`, `plan_scope` ENUM: FREE_ONLY/ALL, `daily_impression_limit` INT nullable, `status` ENUM: DRAFT/ACTIVE/PAUSED/ENDED）。フリープランのみに表示するか全プランに表示するかを `plan_scope` で制御
- `ad_targeting_rules`（将来）: ターゲティング条件（`campaign_id`, `rule_type` ENUM: ORG_CATEGORY/REGION/USER_ROLE/ALL, `rule_value` VARCHAR）。複数条件は AND 結合。rule_type=ALL は全ユーザー対象（ターゲティングなし）
- `ad_impressions` / `ad_clicks`（将来）: インプレッション・クリックの集計ログ（`ad_id`, `campaign_id`, `team_id`, `user_id` nullable, `occurred_at`）。課金計算・効果測定に使用。生ログは90日保持後に物理削除し、日次集計に集約

## コルクボード

- `corkboards`: ボードマスター（`scope_type` ENUM: PERSONAL/TEAM/ORGANIZATION, `scope_id` nullable, `owner_id` nullable（PERSONALの場合はuser_id）, `name`, `background_style` ENUM: CORK/WHITE/DARK, `is_default` BOOLEAN）。個人・チーム・組織それぞれに複数ボードを作成可能
- `corkboard_cards`: カード（`board_id`, `card_type` ENUM: REFERENCE/MEMO/URL/SECTION_HEADER, `ref_type` ENUM nullable: CHAT_MESSAGE/TIMELINE_POST/BULLETIN_THREAD/BLOG_POST/FILE（拡張ENUM: 新機能実装時に Flyway `ALTER TABLE corkboard_cards MODIFY COLUMN ref_type ENUM(...)` で追加。例: SURVEY_RESPONSE/SAFETY_REPORT 等）, `ref_id` BIGINT nullable, `content_snapshot` TEXT nullable（参照元削除後も内容を保持。上限5,000文字を推奨）, `ref_url` VARCHAR nullable, `pos_x` INT, `pos_y` INT, `card_width` INT DEFAULT 200, `card_height` INT DEFAULT 150, `color` ENUM: WHITE/YELLOW/RED/BLUE/GREEN/PURPLE, `user_note` TEXT nullable, `title` VARCHAR nullable, `auto_archive_at` DATETIME nullable, `is_archived` BOOLEAN DEFAULT false, `created_by` FK users）。`SECTION_HEADER` は `title` のみ使用し、他のコンテンツフィールドは NULL。横長タイトルバーとして描画する
- `corkboard_groups`: セクション（折りたたみ可能な名前付きコンテナ）（`board_id`, `name` VARCHAR（セクション見出し）, `pos_x` INT, `pos_y` INT, `group_width` INT, `group_height` INT, `color` ENUM: TRANSPARENT/LIGHT_YELLOW/LIGHT_BLUE/LIGHT_GREEN/LIGHT_PURPLE, `is_collapsed` BOOLEAN DEFAULT false）。`is_collapsed=true` 時はタイトルバーのみ描画しセクション内のカードを非表示にする。カードをセクション内に追加すると `corkboard_card_groups` に紐付けが保存される
- `corkboard_card_groups`: カードとグループの中間テーブル（`card_id`, `group_id`）。1枚のカードは複数グループに属さない設計とし、移動時は旧グループとの紐付けを更新する

## メンション

- メンション（@ユーザー名）はタイムライン・チャット・掲示板など複数機能で横断的に使用されるため、ポリモーフィックテーブル（`target_type` + `target_id`）として設計する。Phase 1 で詳細カラム定義を確定すること

## ユーザーブロック

プラットフォーム共通のユーザーブロック機能。DM・タイムライン・フォロー等の複数機能で横断的に使用する。

- `user_blocks`: ユーザー間のブロック関係（`blocker_id` FK → users ON DELETE CASCADE, `blocked_id` FK → users ON DELETE CASCADE, `created_at`）
  - UNIQUE KEY `uq_user_blocks (blocker_id, blocked_id)` — 同一ペアの重複防止
  - INDEX `idx_user_blocks_blocked (blocked_id, blocker_id)` — ブロックされている側からの逆引き
- **影響範囲（ブロック中の挙動）**:
  - **DM**: ブロック中のユーザーからの DM チャンネル作成を 403 で拒否。既存 DM チャンネルはアーカイブされないが、ブロック中はメッセージ送信不可
  - **フォロー**: ブロック中のユーザーからのフォローを 403 で拒否。既存のフォロー関係は自動解除（`follows` テーブルから DELETE）
  - **パブリックタイムライン**: ブロック中のユーザーの投稿をフィードから除外（API レスポンスでフィルタ）。ブロック中のユーザーのプロフィールページは閲覧不可
  - **メンション**: ブロック中のユーザーからの @メンション通知を抑制（`mentions` INSERT は行うが、プッシュ通知を送信しない）
  - **チーム/組織内の機能**: ブロックはチーム/組織内の業務機能（スケジュール・シフト・掲示板等）には影響しない。業務上の連絡を妨げないため
- **API**:
  - `POST /api/v1/users/blocks` — ブロック追加（`{ "blocked_id": 11 }`）。201 Created
  - `DELETE /api/v1/users/blocks/{blockedId}` — ブロック解除。204 No Content
  - `GET /api/v1/users/blocks` — ブロック一覧取得（自分がブロックしているユーザー一覧）
- **Flyway**: `V1.0XX__create_user_blocks.sql`（Phase 1 の末尾で採番。共通基盤テーブル）
- **監査ログ**: `USER_BLOCKED` / `USER_UNBLOCKED` を記録

## タイムライン・通知

- `notifications`: 既読通知は **90日後** にバッチで物理削除する（大量蓄積防止）
- `notification_preferences`: ユーザーが組織/チームごとに通知受信を ON/OFF する設定（`user_id`, `scope_type`: TEAM/ORGANIZATION, `scope_id`, `is_enabled` DEFAULT true）。レコードが存在しない場合は受信する（opt-out 方式）。将来的にはグループ単位の通知制御（`scope_type`: GROUP）への拡張を検討する
- `timeline_post_attachments`: `attachment_type` は `IMAGE` / `FILE` / `VIDEO_LINK` の VARCHAR。`VIDEO_LINK` は `video_url`（外部URL）・`video_thumbnail`・`video_title` カラムを持ち、ファイルストレージは使用しない
- `timeline_posts`: `reaction_count`・`reply_count` カラムを denormalize で保持し、リアクション追加・削除時にアトミック更新する（COUNT クエリ廃止）。`posted_as_type` で実名/ソーシャルプロフィール名義を区別
- `user_social_profiles`: ユーザーの匿名 SNS アイデンティティ（最大3個/ユーザー）。`handle`（一意）・`display_name`・`avatar_url` を持ち、パブリックタイムラインやブログでの匿名投稿に使用。SYSTEM_ADMIN のみ通報調査時に実ユーザーとの紐付けを確認可能
- `follows`: ポリモーフィックフォローシステム。`follower_type`/`followed_type` が USER/SOCIAL_PROFILE の4パターンをサポート
- `content_reports`: コンテンツ通報テーブル。タイムライン投稿・ブログ記事等の通報を管理

## チャット

- `chat_channels`: `last_message_at`・`last_message_preview`（最新メッセージ冒頭100字）を denormalize で保持し、チャンネル一覧取得時の `chat_messages` JOIN を排除
- `chat_channel_members`: `unread_count` を denormalize で保持し、メッセージ送信時に +1・既読時に 0 リセットするアトミック更新で管理

## スケジュール・出欠

- `schedules`: 三者 XOR 制約（`team_id` / `organization_id` / `user_id` のいずれか1つのみ非 NULL）でスコープを管理。繰り返しルール（`recurrence_rule` JSON）をサポート
- `schedule_attendances`: 出欠回答テーブル。`status`（ATTENDING / PARTIAL / ABSENT / UNDECIDED）で管理
- `event_surveys` / `event_survey_responses`: スケジュールに紐付いた簡易アンケート（出欠確認時の追加質問等）。独立したアンケート機能の `surveys` / `survey_responses`（後述）とは設計上別テーブルとして管理する
- `schedule_cross_refs`: クロスチーム・組織スケジュール招待テーブル（試合マッチング等）

## コンテンツ管理

- `bulletin_threads`: `reply_count` を denormalize で保持し、スレッド一覧取得時の COUNT クエリを廃止
- `bulletin_read_status`: スレッド削除時にカスケード削除する。既読データは投稿から **90日後** にバッチで物理削除する（蓄積量削減）

## ファイル共有

- `shared_files`: チームごとのストレージ使用量を `storage_used_bytes`（BIGINT, bytes）カラムで denormalize 管理。ファイルアップロード時に加算・削除時に減算するアトミック更新で維持する
- ストレージ上限・価格は `storage_plans` で一元管理する（ハードコードしない）。超過時はアップロードを拒否しプランアップグレードを促す

## ストレージ課金

- `storage_plans`: ストレージプランの定義。SYSTEM_ADMINが管理画面から全項目を設定・変更可能
  - `name`（例: フリー / スタンダード / プロ）
  - `included_bytes` BIGINT（無料枠。例: 5GB = 5,368,709,120）
  - `price_monthly` DECIMAL（月額料金。0 = 無料枠）
  - `price_yearly` DECIMAL（年額料金）
  - `price_per_extra_gb` DECIMAL（無料枠超過分の従量単価。nullable = 超過アップロード不可）
  - `max_bytes` BIGINT nullable（ハードキャップ。null = 従量課金で無制限）
  - `is_active` BOOLEAN
- `team_storage_subscriptions`: チームが加入中のストレージプラン（`team_id`, `storage_plan_id`, `billing_cycle`: MONTHLY/YEARLY, `status`, `current_period_end`）。ストレージプランとモジュールサブスクリプションは独立して管理する

## 決済・会費

- `payment_items`: 支払い項目定義（年会費/月謝/アイテム代金/寄付）。`team_id` / `organization_id` の XOR 制約でスコープを管理
- `stripe_customers`: ユーザーの Stripe 顧客 ID 管理（1ユーザー1レコード、遅延生成）
- `member_payments`: 支払い記録（Stripe 自動 / ADMIN 手動）。Stripe 決済の `payment_intent_id` を保持
- `team_access_requirements` / `organization_access_requirements`: チーム/組織全体ロックに必要な支払い項目の紐付け
- `content_payment_gates`: コンテンツ単位のアクセスゲート設定（ポリモーフィック）
- 拡張設計: 物販テーブル群（`products`, `orders`, `order_items` 等）は Phase 8 以降に別セクションとして追加する

## 監査ログ

- 大量蓄積を防ぐため保持期間を **2年**（設定変更可）とし、期限超過分はバッチ処理でアーカイブまたは物理削除する

## 緊急安否確認

- `safety_checks`: 安否確認セッション（`scope_type`: TEAM/ORGANIZATION/GROUP, `scope_id`, `created_by`, `status`: ACTIVE/CLOSED, `reminder_interval_minutes`, `bulletin_thread_id` FK → `bulletin_threads`）。実行と同時に専用掲示板スレッドを自動生成し `bulletin_thread_id` に保存する
- `safety_responses`: 個人の回答（`safety_check_id`, `user_id`, `status` ENUM: SAFE/NEED_SUPPORT/OTHER, `message` TEXT nullable, `gps_shared` BOOLEAN DEFAULT false, `gps_latitude` DECIMAL(10,7) nullable, `gps_longitude` DECIMAL(10,7) nullable, `responded_at`）。回答保存後に WebSocket で掲示板スレッドの集計情報をリアルタイム更新する

## 議決権行使・委任状

- `proxy_votes`: 議案・投票セッション（タイトル・期限・定足数・集計結果）を管理。個人の投票回答（`user_id`, `vote_type`: ATTEND/APPROVE/REJECT/ABSTAIN）のカラム設計は Phase 1 で確定すること
- `proxy_delegations`: 委任状（委任者 `user_id`・代理人 `delegate_user_id`・対象議案・白紙委任フラグ・電子印鑑記録）を管理
- 投票の秘匿性: `is_anonymous` フラグで無記名/記名を切り替え可能。無記名時は集計結果のみ公開し、個人の投票内容は ADMIN にも非公開とする

## 住民台帳・物件情報

- `dwelling_units`: 部屋番号・種別・間取り等の居室マスター
- `resident_registry`: 区分所有者/賃借人の区別・入退居日を管理。`is_public=false` で ADMIN/DEPUTY_ADMIN のみ閲覧可
- `property_listings`: 居住者間の物件売買/賃貸希望の掲示（`listing_type`: SALE/RENT）。駐車場区画の譲渡希望は `parking_listings` で管理するため本テーブルには含まない

## カルテ

- `chart_records`: カルテ本体（来店日・担当スタッフ・次回推奨メモ・顧客共有フラグ・アレルギー禁忌情報）
- `chart_intake_forms`: 問診票・同意書（電子印鑑と連携。初回 or 毎回更新）
- `chart_photos`: ビフォーアフター写真（`photo_type`: BEFORE/AFTER, `is_shared_to_customer`）。写真は S3 に保存し CloudFront **署名付きURL（Signed URL）**でのみアクセス可能にする（医療・美容記録のため公開 URL は使用しない）。1カルテあたり最大20枚を推奨上限とする
- `chart_body_marks`: 身体チャートのマーク情報（整骨院向け。座標・種別・メモ）
- `chart_formulas`: カラー・薬剤レシピ（美容室向け。薬剤名・配合比率・放置時間・パッチテスト記録）
- `chart_section_settings`: チームごとのセクション ON/OFF 設定（`team_id`, `section_type` ENUM: `INTAKE_FORM` / `ALLERGY` / `PHOTOS` / `STAFF` / `BODY_CHART` / `FORMULA` / `PATCH_TEST` / `PROGRESS_GRAPH` / `NEXT_MEMO`, `is_enabled`）
- `chart_custom_fields`: カスタム項目定義（`team_id`, `field_name`, `field_type`: TEXT/NUMBER/DATE/SELECT/CHECKBOX, `sort_order`）。1チームにつき最大5件

## 駐車場区画管理

- `parking_spaces`: 区画番号・種別・`price_per_month`（個別価格設定）・`status`（VACANT/OCCUPIED/MAINTENANCE）
- `registered_vehicles`: ユーザーの車両登録（`user_id`, `vehicle_type`: CAR/MOTORCYCLE/BICYCLE, `plate_number`（個人情報のため暗号化して保存）, `nickname` nullable）。1ユーザーにつき最大3台まで登録可能
- `parking_assignments`: 車両（`vehicle_id`）と区画の紐付け。複数区画の一括割り当てに対応
- `parking_applications`: 空き区画への申請・抽選エントリー
- `parking_listings`: 区画の譲渡・売買希望リスト

## 外部連携・広告

- SNS フィードキャッシュ（Instagram/X API レスポンス）は揮発性データのため MySQL テーブルではなく **Redis**（key: `sns_feed:{teamId}:{provider}`、TTL: 15分）で管理する
