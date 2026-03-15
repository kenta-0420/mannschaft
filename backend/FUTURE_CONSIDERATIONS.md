# 今後の検討事項・開発時の注意点

各フェーズの開始時に該当する項目を確認し、必要に応じてREADME.mdや設計に反映する。

> **ドキュメント優先順位**
> - **README.md が唯一の正（Single Source of Truth）**。本ファイルと README.md の記述が矛盾する場合は README.md を優先する
> - 本ファイルは「未確定事項・将来検討事項の一時置き場」。フェーズ開始時に設計が確定した内容は README.md へ移行し、本ファイルから削除する
> - README.md 内の `将来:` 注釈が本ファイルの記述と重複する場合も、README.md の記述を正とする

---

## 開発時のボトルネック・対策

### 1. テーブル数の多さ（80+テーブル）
- Flywayマイグレーションの管理が複雑化する
- **対策**: Phase 1でER図を確定させ、Phaseごとにマイグレーションファイルを分割管理
- マイグレーションファイルの命名規則: `V{phase}.{連番}__{説明}.sql`（例: `V1.001__create_users_table.sql`）

### 2. 権限制御の複雑さ
- 5ロール × 3層アカウント × グループ階層 × モジュールON/OFF の組み合わせが膨大
- **対策**: Spring Securityのカスタム権限評価を Phase 1 で堅牢に設計し、テストケースを厚くする
- パーミッション評価のキャッシュ戦略（Redis）も早期に検討

### 3. リアルタイム通信の負荷
- チャット + 通知 + プッシュ通知が全てWebSocket/Redis経由
- 同時接続数が増えた時のRedisブローカーのスケーリング設計が必要
- **対策**: 負荷テストをPhase 4（チャット実装時）に実施

### 4. Pre-signed URLのファイルアップロード
- 大容量ファイルのチャンクアップロード + Pre-signed URL の組み合わせは実装が複雑
- **検討事項**: ファイルサイズ制限、ウイルススキャン、サムネイル自動生成パイプライン
- S3互換ストレージの選定（AWS S3 / MinIO / Cloudflare R2 等）

### 5. 決済機能のリスク（Phase 8）
- Stripe等の外部決済サービスとの連携には特定商取引法やPCI DSS準拠など法的・技術的ハードルがある
- **対策案**: まずは集金管理（手動記録）から始め、段階的に自動決済を導入
- 決済代行サービスの選定、利用規約・プライバシーポリシーの整備

### 6. OAuth2/ソーシャルログイン連携
- Google / LINE / Apple それぞれのOAuth2フロー実装が必要
- Apple Sign In は Web での実装がやや複雑
- **対策**: Spring Security OAuth2 Client を活用し、プロバイダー追加を容易にする設計

---

## 将来検討する機能・改善

### 組織種別の視覚的表現（NONPROFIT / FORPROFIT）

組織プロフィールページ等で、訪問者が一目で組織の種別（非営利・営利）を認識できる視覚表現を検討する。
単なる「バッジ」ではなく、その組織を応援したくなる・見て気持ちよくなれるような演出を目指す。

**検討の方向性（画面作成時に具体化）**
- 非営利団体には温かみのある特別な見出しデザイン・カラーテーマを適用（例: ゴールド系のグラデーション背景・ロゴ周囲のアクセント）
- 「社会貢献組織」であることをさりげなく伝えるコピーや小さなアイコン（バッジ的にならないよう注意）
- ページを開いた瞬間にわかるトーン＆マナーの違い（フォント・余白・カラーの統一感）
- 営利組織はシャープ・プロフェッショナル寄り、非営利はあたたかみ・コミュニティ感を重視

**前提条件**
- `org_type` が SYSTEM_ADMIN に承認された NONPROFIT のみが対象（未承認は FORPROFIT 扱い）
- フロントエンドは `organizations.org_type` を参照してスタイルを切り替える

### 物販（EC）機能

> ※ 組織レベルの年会費徴収は F04 で `payment_items.organization_id` として設計済み（Phase 3）

#### 物販（EC）機能
- 組織・チームが独自のグッズ・デジタル商品等を販売できるオンラインショップ
- 必要テーブル例: `products`（商品マスター）/ `product_variants`（サイズ・色等バリエーション）/ `orders`（注文）/ `order_items`（注文明細）/ `shipping_info`（配送情報）
- 決済機能（Stripe 等）と連携し、売上管理・在庫管理も一元化
- 決済機能（Phase 8）が安定したあとの拡張候補

### 広告プラットフォームの本格展開

現段階（Phase 9）の実装はAmazonアソシエイトの静的バナー表示のみ。将来的に本格的な広告プラットフォームへ拡張する。

#### 内部広告プラットフォーム
- 組織アカウントが広告主として登録し、自チーム/組織のページへの誘導広告を出稿できる機能
- 広告クリエイティブ（画像・タイトル・リンク先）をADMINが申請 → SYSTEM_ADMINが審査・承認するフロー
- リンク先: プラットフォーム内の組織/チームページ（`INTERNAL_ORG`）または外部URL（`EXTERNAL_URL`）を選択可能
- キャンペーン管理: 掲載期間・1日あたりインプレッション上限・フリープランのみ/全プランへの表示範囲を設定

#### ターゲティング広告
- 組織カテゴリ（スポーツ/整骨院/学校等）・地域・ユーザーロールに応じた広告の絞り込み配信
- 広告配信ロジック: バックエンドがチームの `template_type` / `region` / リクエストユーザーのロールを取得し、`ad_targeting_rules` と照合して最適な広告を返す
- A/Bテスト: 同一キャンペーン内で複数クリエイティブを用意し、インプレッションを分割して効果比較

#### 収益モデル
- **初期（アフィリエイト）**: Amazonアソシエイトのクリック報酬（CPS: 売上連動型）
- **将来（CPM/CPC課金）**: 広告主の組織から1,000インプレッションあたり or 1クリックあたりの費用を徴収。決済機能（Phase 8）安定後に導入を検討
- **フリープラン限定表示**: 有料プランへのアップグレードインセンティブとして機能させる設計

#### 必要テーブル（追加時に設計確定）
- `ad_daily_stats`（日次集計: `ad_id`, `date`, `impression_count`, `click_count`, `ctr`）

#### プライバシー・法的対応（設計確定前に必須検討）

`ad_clicks` / `ad_impressions` テーブルに `user_id` と行動履歴を記録する場合、以下の法的・運用的要件を満たす必要がある。**ターゲティング広告実装フェーズの設計開始前に確認・設計すること。**

- **個人情報保護法（日本）への対応**
  - クリック・閲覧履歴は「個人関連情報」に該当する可能性があり、第三者（広告主）への提供時は本人の同意またはオプトアウト手段の提供が必要
  - 統計化・匿名化した集計データ（`ad_daily_stats`）のみを広告主に開示する設計が望ましい
  - 個人を特定できるクリックログ（`user_id` 付き）の保持期間を定め、必要最小限に制限する
- **利用規約・プライバシーポリシーへの記載**
  - 広告配信目的での行動履歴の利用、収集するデータ種別、保持期間、開示先を明記する
  - フリープランユーザーへの広告表示と引き換えに行動データを収集する旨を明確に同意取得する
- **オプトアウト設計**
  - ログイン済みユーザーがクリック・インプレッション追跡をオプトアウトできる設定項目の提供を検討する
  - オプトアウト時は `user_id` を記録せず匿名ログ（`user_id=NULL`）として記録する
- **データ最小化**
  - 詳細な行動ログ（`ad_clicks` / `ad_impressions`）は日次集計後、一定期間（例: 90日）で物理削除し、`ad_daily_stats` のみを長期保持する設計を推奨

### トークン（コイン）制度 — 投げ銭・団体支援プラットフォーム

運営がトークン（コイン）を一括発行し、ユーザーがそれを購入して任意の組織・チームへ送る「投げ銭」型の団体支援機能。Phase 3 の DONATION 型（単純な寄付）の上位互換として位置づける。

#### 想定機能
- 運営がトークンを一括発行（プリペイド式）。ユーザーが Stripe で購入
- ユーザーが任意のチーム/組織へトークンを送付（リアルタイム演出: ライブ配信・試合中の応援等）
- チーム/組織の ADMIN がトークンを現金化（出金申請 → 運営承認 → 振込）
- 送付履歴・受領履歴の管理、ランキング表示

#### 法的リスク（実装前に法務確認必須）
- **資金決済法 — 前払式支払手段**: トークンが「前払式支払手段」に該当する場合、発行保証金の供託義務（発行額1000万円超で未使用残高の半額）、届出義務（第三者型の場合は登録制）が発生する
- **資金移動業**: ユーザー→団体への送金仲介が「資金移動業」に該当する場合、金融庁への登録が必要
- **Stripe Connect**: 団体への資金分配には Stripe Connect（プラットフォーム型決済）の導入が必要。Phase 3-4 の Checkout/Subscription とはアーキテクチャが異なる
- **景品表示法**: トークン購入時のボーナス（おまけトークン）が景品規制に該当する可能性

#### 必要テーブル（設計確定前の暫定案）
- `token_packages`（販売単位: 100コイン=100円、500コイン=450円 等）
- `user_token_balances`（ユーザーの残高管理）
- `token_transactions`（購入・送付・受領・出金の全履歴）
- `team_token_balances` / `org_token_balances`（団体の受領残高）
- `withdrawal_requests`（出金申請）

#### 前提条件
- Phase 3 の DONATION 型で寄付の需要・利用頻度を検証した上で、トークン制度の導入を判断する
- 法務確認が完了するまで実装に着手しない
- 別フィーチャードキュメント（F12 等）として独立して設計する

### 電子印鑑 — 角印（組織印）の追加

Phase 5 では個人の丸印（認印）のみを実装。将来、組織として公式に承認した証跡が必要になった場合に角印を追加する。

- `electronic_seals` に `seal_type` ENUM(`ROUND`, `SQUARE`) カラムを追加
- 角印は組織名を刻む（`organizations.name` をソースとする）
- 用途例: 組織間契約書、公式声明への承認、外部向け証明書
- 角印の押印権限: ADMIN のみ（組織を代表する印鑑のため）

### シフト管理 — `assigned_user_ids` の正規化テーブル移行

`shift_slots.assigned_user_ids`（JSON 配列）は現行設計で問題なく動作するが、`GET /shifts/my` がユーザーのシフトを全チームから横断検索する際に `JSON_CONTAINS` のフルスキャンがボトルネックになる可能性がある。Redis キャッシュ（`mannschaft:cache:user-shifts:{userId}`、TTL 30分）で緩和するが、キャッシュミス時のレイテンシ突発悪化が懸念される。

**移行候補**: `shift_slot_assignments` 正規化テーブル
- `slot_id` BIGINT UNSIGNED FK → shift_slots (ON DELETE CASCADE)
- `user_id` BIGINT UNSIGNED FK → users (ON DELETE CASCADE)
- UNIQUE KEY `uq_slot_assignments (slot_id, user_id)`
- INDEX `idx_slot_assignments_user (user_id, slot_id)` — `GET /shifts/my` の高速化

**移行判断基準**: チーム数 × シフト枠数が増加し、Redis キャッシュミス時のクエリが 100ms を超えるようになった場合に移行を検討する。

### 予約管理 — シフト確定時の予約枠自動生成（Phase 5+）

Phase 3 ではシフトと予約枠の連携は参照のみ（シフト外の枠作成時に警告表示するがブロックしない）。シフトの粒度（勤務時間帯）と予約枠の粒度（30分メニュー単位）が異なるため、マッピングルールの設計が複雑。シフト管理と予約管理の両方が安定稼働した後に検討する。

- `ShiftPublishedEvent` を購読し、確定シフトの勤務時間帯からデフォルトメニューの予約枠を自動生成するリスナーを追加
- ADMIN が自動生成ルールを設定（例: 「田中スタッフの午前シフト → 整体60分コースの枠を30分間隔で自動作成」）
- 自動生成された枠は `is_auto_generated = TRUE` で識別し、ADMIN が個別に編集可能

### 予約管理 — メニューマスターテーブル（Phase 5+）

Phase 3 では予約枠の `title` はフリーテキスト。多業種対応（整骨院・飲食店・ジム等）のため、汎用的なメニューマスター設計が困難。フロントエンドの入力サジェスト（過去タイトル候補表示）で入力の一貫性を補完する。利用パターンが蓄積された段階でマスター化を検討する。

- `reservation_menus` テーブル: id, team_id, name, duration_minutes, price, description, display_order, is_active
- 予約枠作成時に `menu_id` を指定すると `title` / `price` / 枠の長さを自動設定
- メニュー CRUD API（`/api/v1/teams/{teamId}/reservation-menus`）

### 予約管理 — キャンセル待ち機能（Phase 5+）

Phase 3 では `FULL` → キャンセル → `AVAILABLE` に自動復帰する仕組みでMVPは十分。利用規模が成長し、人気枠が常に FULL になるほどの需要が出てから実装する。

- `reservation_waitlist` テーブル: id, reservation_slot_id, user_id, line_preference_id (nullable), position, status, notified_at, expires_at, created_at
- 空きが発生したら待ちリストの先頭ユーザーに自動通知（`notification_type = RESERVATION_WAITLIST_AVAILABLE`）
- 通知後の有効期限（例: 30分）以内に予約しない場合、次の待ちユーザーに通知を繰り上げ
- ADMIN 設定: `waitlist_enabled`（デフォルト: FALSE）、`waitlist_max_per_slot`（1枠あたりの最大待ち人数）

### 予約管理 — 再予約ショートカット（Phase 5+）

リピーターが多い業種（整骨院・美容室等）向けに「前回と同じ内容で予約」のショートカット機能。`GET /reservations/my` のレスポンスに `is_rebookable`（同じ枠タイトル×スタッフで未来の AVAILABLE 枠があるか）を追加済み。

- フロントエンドで「同じ内容で再予約」ボタンを表示
- ボタン押下で `POST /reservations` のリクエストを自動構築（前回の `title` / `staff_user_id` に一致する最も近い AVAILABLE 枠を選択）
- お気に入りスタッフ機能（`user_favorite_staff` テーブル）との連携も検討

### 予約管理 — 過去予約アーカイブ戦略（Phase 10）

`reservations` テーブルは時間経過で肥大化し、インデックス性能に影響する。Phase 10（運用最適化）で対応する。

- `slot_date` が12ヶ月以上前の `COMPLETED` / `NO_SHOW` / `CANCELLED` 予約を月次バッチでコールドテーブル（`reservations_archive`）に移行
- `reservation_reminders` も対象予約に紐づくレコードを同時にアーカイブ
- `/reservations/my` のデフォルト検索範囲は30日のため通常運用に影響なし
- 統計 API（`/reservations/stats`）は必要に応じてアーカイブテーブルも UNION で参照
- アーカイブ対象の判定: `reservation_slots.slot_date < NOW() - INTERVAL 12 MONTH`

### 予約管理 — 予約枠一括作成 API（Phase 5+）

Phase 3 では繰り返しルール（WEEKLY 等）で大部分の定期枠をカバー。初期運用で繰り返しルールだけでは対応しきれないパターン（曜日ごとに異なる枠数・スタッフ構成等）が頻発する場合に導入する。

- `POST /api/v1/teams/{teamId}/reservation-slots/bulk` で複数枠を1リクエストで作成
- リクエスト: `{ "slots": [{ "staff_user_id": 456, "title": "...", "slot_date": "...", "start_time": "...", "end_time": "..." }, ...] }`（最大50枠/リクエスト）
- バリデーション: 各枠に対して単発枠作成と同じチェックを実行。1枠でもエラーがあれば全体をロールバック（all-or-nothing）
- フロントエンド: 「週間テンプレート」UI（曜日×時間帯のグリッドでまとめて選択→一括作成）

### アンケート — 設問の条件分岐（Phase 7+）

Phase 5 ではアンケートの設問は全問フラット表示。利用パターンが蓄積され、複雑な調査ニーズが顕在化した段階で導入を検討する。

- `survey_questions` に `condition_question_id` / `condition_option_ids` カラムを追加
- 「Q1 で『はい』を選んだ場合のみ Q2 を表示」のようなスキップロジック
- フロントエンド: 回答内容に応じて設問の動的表示/非表示を制御
- バリデーション: 非表示設問の必須チェックをスキップ

### アンケート — AI によるブログ記事生成（Phase 9+）

Phase 5 ではテンプレート方式（Thymeleaf / String.format）でアンケート結果をブログ下書きに変換する。テンプレートだと機械的な列挙になるため、将来的に AI API を使って自然な記事文体に変換する機能を追加する。

- `POST /surveys/{id}/generate-blog-draft` にオプション `use_ai: true` を追加
- Claude API / OpenAI API に集計データを送信し、読みやすい記事本文を生成
- プロンプト: 「以下のアンケート結果を、組織内ブログ記事として分かりやすくまとめてください。」+ 集計 JSON
- 生成結果はそのまま `DRAFT` 保存（ADMIN が必ず確認・編集してから公開）
- コスト: 1記事あたり数円程度（トークン数に依存）。無制限に生成されないよう1アンケートあたり3回までの生成上限を設定

### アンケート — テンプレート（定型設問の再利用）（Phase 7+）

Phase 5 では毎回設問を手動作成。同じ種類のアンケートを繰り返し実施するチーム（顧客満足度調査、イベント後アンケート等）が増えた段階で導入する。

- `survey_templates` テーブル: id, scope_type, scope_id, title, questions JSON, created_by, is_public
- アンケート作成時に「テンプレートから作成」を選択 → 設問・選択肢を事前入力
- SYSTEM_ADMIN が管理するプラットフォーム共通テンプレート（`is_public = TRUE`）も提供可能

### シフト管理 — Google カレンダー自動同期

Phase 3 ではシフト確定後の Google カレンダー同期は実装しない。`ShiftPublishedEvent` を発行する設計は組み込み済みのため、将来このイベントを購読して Google Calendar API に連携するリスナーを追加するだけで対応可能。

- `user_calendar_sync_settings` に `sync_shifts BOOLEAN DEFAULT FALSE` カラムを追加（ユーザーが個別に ON/OFF）
- 同期対象: `PUBLISHED` 状態のシフトのうち、自分が `assigned_user_ids` に含まれるスロット
- イベント生成: slot_date + start_time/end_time → Google Calendar Event として同期
- 公開後に `assigned_user_ids` が変更された場合: `ShiftAssignmentChangedEvent` を購読して該当イベントを更新/削除

### チャット — 画像サムネイル自動生成

Phase 4 ではチャット添付画像はフロントエンドの `<img loading="lazy">` + CSS リサイズ（`max-width: 400px`）で表示。画像投稿量が増えてパフォーマンスが問題になった段階でサーバーサイドサムネイル生成を導入する。

- S3 Event Notification → Lambda（Sharp / ImageMagick）→ サムネイル保存（`uploads/chat/thumbnails/{uuid}_thumb.webp`）
- `chat_message_attachments` に `thumbnail_key VARCHAR(500) NULL` カラムを追加（Flyway ALTER TABLE）
- サムネイルサイズ: 幅400px（アスペクト比維持）、WebP 形式（ファイルサイズ削減）
- フロントエンド: `thumbnail_key` が存在すればサムネイル URL を表示、なければ元画像を遅延読み込み

### QR会員証 — Apple Wallet / Google Pay 連携

デジタル会員証をネイティブのウォレットアプリに追加できる機能。PWA では対応困難なため、ネイティブアプリ化（Phase 11+）と同時に検討する。

- Apple Pass (pkpass) ファイルの生成 API（`GET /member-cards/{id}/wallet/apple`）
- Google Pay Pass の生成 API（`GET /member-cards/{id}/wallet/google`）
- Pass 内に QR コードを埋め込み、ロック画面から直接表示可能
- Pass の更新: `card_number` / `display_name` 変更時に Push Notification で Pass を自動更新
- `qr_secret` 再生成時は旧 Pass を無効化し、新 Pass をウォレットに再登録するフローが必要

### QR会員証 — レジ / POS 連携（Phase 8+）

チェックインと売上データを紐付け、メンバー別の購買履歴・累計売上を管理する機能。Phase 8 の決済基盤が整った段階で実装する。

**概要**:
- チェックイン時に「会計セッション」を自動開始し、レジで会計完了時にセッションをクローズ
- `member_card_checkins.transaction_id` で売上データと紐付け（Phase 2 でカラムを先行追加済み）
- `member_cards.total_spend`（累計売上額。Phase 2 でカラムを先行追加済み）を会計完了時にアトミック更新

**必要テーブル（Phase 8 で設計確定）**:
- `transactions`: id, team_id, member_card_id (nullable), amount, tax, payment_method, status, created_at
- `transaction_items`: id, transaction_id, item_name, unit_price, quantity, subtotal
- `daily_sales_summary`: team_id, date, total_amount, transaction_count, member_checkin_count（日次集計）

**想定 API**:
- `POST /teams/{teamId}/transactions` — 会計登録（チェックインと自動紐付け）
- `GET /teams/{teamId}/transactions` — 売上一覧
- `GET /teams/{teamId}/sales/stats` — 売上統計（日別・月別・メンバー別）
- `GET /member-cards/{id}/purchase-history` — メンバー別購買履歴

**メンバー別分析（ADMIN ダッシュボード）**:
- 来店頻度 × 平均客単価のマトリクス（常連×高単価 / 新規×低単価 等のセグメント分析）
- LTV（顧客生涯価値）推定: `total_spend / 所属月数 × 平均継続月数`
- 休眠会員の検出: `last_checkin_at` が N 日以上前のアクティブ会員をリストアップ

### AI活用
- チャットボットによるFAQ自動応答
- パフォーマンスデータの分析・レコメンド
- 投稿内容の自動モデレーション
- **コルクボードのAI要約**: 参照カードとして長いスレッド（チャット・掲示板）を保存する際に、3行程度の要約をカード上に自動生成して表示。OpenAI API等を活用し、カード作成時に非同期で要約を生成・保存する（`corkboard_cards.ai_summary TEXT nullable`）

### ネイティブアプリ化
- PWAでの運用が限界に達した場合、React Native / Flutter 等でのネイティブアプリ化を検討
- プッシュ通知の安定性向上（FCM / APNs 直接連携）

### 緊急地震速報連動の安否確認自動配信（Phase 9）

気象庁の緊急地震速報を受信し、安否確認を自動配信する機能。F03.6 の手動安否確認の自動トリガー拡張として Phase 9（外部連携）で実装する。

#### 基本仕様
- **データソース**: 気象庁防災情報 XML（WebSocket / ポーリング）、または P2P地震情報 API（WebSocket）
- **自動配信の閾値**: 震度5弱以上で自動配信。SYSTEM_ADMIN が管理画面から閾値を変更可能
- **震度6強以上は強制配信**: チーム/組織の自動配信 OFF 設定に関わらず、震度6強以上では全チーム/組織に強制配信する（SYSTEM_ADMIN レベルのオーバーライド）
- **地域マッチング**: チーム/組織の所在地（都道府県 or 郵便番号）と気象庁の地域コードを紐付け、対象地域に所在するスコープのみに配信
- **自動実行の安否確認**: `safety_checks.created_by = NULL`（システム自動実行）、`title` に「【自動】緊急地震速報: {地域名} 震度{N}」を自動設定

#### 自動配信 ON/OFF 設定
- **チーム/組織単位の設定**: ADMIN がチーム/組織設定画面で「緊急地震速報による安否確認の自動配信」を ON/OFF 可能（デフォルト: ON）
- **用途**: 群発地震（例: 震度5クラスが連日発生）が続く際に、頻繁な自動配信がスパム化するのを防ぐため、ADMIN が一時的に OFF にできる
- **OFF 時の挙動**: 震度5弱〜6弱の場合は自動配信しない。**震度6強以上は OFF 設定を無視して強制配信**（安全性を最優先）
- **設定テーブル**: `teams` / `organizations` に `auto_safety_check_enabled BOOLEAN NOT NULL DEFAULT TRUE` カラムを追加（Phase 9 の Flyway マイグレーションで ALTER TABLE）

#### 連続地震の重複配信防止
- 同一スコープに対して直前の自動配信から30分以内に次の地震速報が来た場合は新規セッションを作成しない（短時間の余震による重複配信を防止）
- ただし震度6強以上の場合は重複防止を無視して常に新規セッションを作成

#### 前提条件
- `teams` / `organizations` に所在地情報（都道府県 or 郵便番号）が登録済みであること
- 気象庁 XML フィードの仕様変更に追従するメンテナンス体制が必要
- 日本国内限定の機能。海外展開時は他国の災害警報 API に差し替える設計が必要

#### 必要テーブル（Phase 9 で設計確定）
- `earthquake_alert_configs`（SYSTEM_ADMIN 設定: 閾値、データソース URL、ポーリング間隔）
- `earthquake_alert_logs`（受信した地震速報の履歴: 地域コード、震度、配信チーム数、タイムスタンプ）
- `teams.auto_safety_check_enabled` / `organizations.auto_safety_check_enabled`（ALTER TABLE で追加）

### 外部サービス連携の拡充
- Slack / Discord 連携（通知転送）
- Zoom / Google Meet 連携（オンラインミーティング）
- 会計ソフト連携（freee / マネーフォワード等）

### データ分析・レポート
- 組織全体のアクティビティ分析ダッシュボード
- 月次・年次レポートの自動生成
- メンバーのエンゲージメント分析

### アクセシビリティ
- WCAG 2.1 AA準拠
- スクリーンリーダー対応
- ハイコントラストモード

---

## フェーズごとのチェックリスト

### Phase 1 開始前
- [ ] ER図の確定と全テーブルのカラム定義
- [ ] Spring Security 権限設計の詳細仕様
- [ ] 開発環境のDocker Compose構成（MySQL + Redis + MinIO）
- [ ] CI/CD パイプライン構築（GitHub Actions + Testcontainers対応）
- [ ] コーディング規約の共有と適用
- [ ] OpenAPI → Zod スキーマ自動生成ワークフローの構築（`openapi-zod-client` 等の選定・導入）
- [ ] 論理削除カラム（`deleted_at`）の適用テーブル一覧の確定
- [ ] BaseEntity / SoftDeletableEntity の実装（`.claudecode.md` §11 参照）
- [ ] `mentions` テーブルの詳細カラム定義の確定（ポリモーフィック設計）
- [ ] `CursorPagedResponse` の実装（`.claudecode.md` §12 参照）

### Phase 4 開始前（チャット）
- [ ] WebSocket + Redis のスケーリング設計
- [ ] 負荷テスト計画の策定
- [ ] メッセージの保持期間ポリシー（下記方針で設計確定すること）

#### チャットメッセージ保持方針（設計案）

メッセージを「ティア」で管理し、古いメッセージはチーム/組織のストレージ枠（`storage_plans`）を消費する方式を採用する。サーバー側の無期限保持を避けつつ、課金とデータ保全を自然に連動させる。

| ティア | 対象 | 保持場所 | 課金 |
|-------|------|---------|------|
| **ホット** | 直近 **12ヶ月** | MySQL（フルテキスト検索・既読管理が可能） | 無料 |
| **コールド** | 12ヶ月超 | S3（圧縮アーカイブ。チームのストレージ枠を消費） | ストレージ枠内は無料。枠超過分はストレージ課金 |
| **削除** | ストレージ枠が不足している場合 | 古いメッセージから順に物理削除（管理者が事前に通知を受け選択可能） | — |

**ブラウザ側のキャッシュ（IndexedDB）**
- 直近 N 件をブラウザの IndexedDB にキャッシュし、**オフライン閲覧・高速スクロール**を実現する
- あくまで「表示キャッシュ」の位置づけ。ブラウザがキャッシュをクリアした場合はサーバーから再取得する
- ブラウザの IndexedDB はユーザーが消去可能・デバイス間で同期されないため、**長期保存の代替にはならない**

**設計上の決定事項（Phase 4 開始前に確定）**
- [ ] ホットティアの期間（暫定: 12ヶ月）の確定
- [ ] コールドアーカイブ移行バッチの設計（移行タイミング・圧縮フォーマット・S3 パス設計）
- [ ] ストレージ枠不足時の管理者通知フローと猶予期間の設計
- [ ] コールドメッセージの検索・閲覧 UI（ホットと同一 UI で透過的に表示するか、専用の「アーカイブ検索」にするか）
- [ ] IndexedDB キャッシュの保持件数上限（例: チャンネルごとに直近 500 件）の確定

### Phase 8 開始前（サービス履歴テンプレート上限の段階制）
- [ ] サービス記録テンプレートの上限をプラン別に設定する（デフォルト10件。有料プランで引き上げ）
- [ ] 上限値の管理方法を確定する（`subscription_plans` にカラム追加 or `module_plan_limits` 汎用テーブル）
- [ ] 具体的なプラン段階と上限数を決定する（例: 無料=10件 / スタンダード=30件 / プロ=無制限）

### 議決権行使 — 重み付け投票（Phase 9+）

Phase 8 では1人1票で実装。株式会社・投資組合等で出資比率に応じた議決権重み付けが必要になった場合に対応する。

- `proxy_vote_sessions` に `is_weighted BOOLEAN DEFAULT FALSE` カラムを追加
- `proxy_vote_weights` テーブル新設: `session_id`, `user_id`, `weight DECIMAL(10,2)`（例: 出資10口 → weight=10.00）
- 集計ロジック変更: `COUNT(*)` → `SUM(weight)`。定足数チェック・可決要件判定の全箇所を切替
- 委任投票の重み: 委任者の weight が代理人の投票に加算される
- 導入判断基準: 利用チームから重み付け投票の要望が複数件出た段階で実装を検討

### Phase 8 開始前（決済）
- [ ] 決済代行サービスの選定（Stripe 推奨。Webhook による課金イベント処理を実装）
- [ ] 特定商取引法に基づく表記の準備
- [ ] セキュリティ監査の実施計画
- [ ] `subscription_plans`（プラットフォーム課金）と `payment_plans`（チーム内会費）の役割分担を最終確認し、スキーマのカラム定義を確定する
- [ ] `module_prices` / `plan_packages` の初期データ（種別・価格）を確定する
- [ ] `discount_campaigns` のクーポンコード生成方式（SecureRandom等）を確定する
- [ ] `tax_settings` の初期値（消費税10%）を Flyway シードデータとして投入する
- [ ] プラン変更時（アップグレード・ダウングレード）の日割り計算ロジックを設計する
- [ ] 請求書 PDF の自動生成・メール送信フローを設計する（税抜・税込・適用割引を明記）
- [ ] 解約時のモジュールアクセス権限の失効タイミング（即時 vs 期末）を決定する
- [ ] ダイレクトメール配信の週次送信上限（現在: 週3回）の有料プラン設計（上限緩和・無制限等）を確定する
- [ ] `storage_plans` の初期データ（無料枠容量・価格）を Flyway シードデータとして確定する
- [ ] ストレージ使用量の超過検知タイミングを設計する（アップロード前チェック vs バッチ集計）
- [ ] ストレージプランのダウングレード時（使用量 > 新プラン上限）の猶予期間・通知フローを設計する
- [ ] `org_count_billing_tiers` の初期データ（NONPROFIT: 無料枠20・超過単価TBD / FORPROFIT: 無料枠5・単価200円）を Flyway シードデータとして投入する
- [ ] 組織数課金の月次バッチを設計する（集計タイミング・アクティブチーム数のカウントロジック）
- [ ] モジュール課金と組織数課金を合算した請求書 PDF の生成フローを設計する
- [ ] `org_type` 変更申請のフロー（申請 → SYSTEM_ADMIN承認 → 翌月課金反映）を確定する
- [ ] 非営利団体への `org_type=NONPROFIT` 設定に必要な証明（法人登記等）の確認・審査フローを設計する
- [ ] 課金タイミングルール（月のどこで契約しても当月は課金対象・日割りなし）をStripe等の決済基盤で実装する方法を確認する
- [ ] ダッシュボード課金サマリーウィジェットの `/billing/current-month` APIが返すレスポンス形式（課金軸ごとの内訳・税込合計）を確定する

### ブログ記事の有料販売（Phase 8+）

個人ブログ記事を有料で販売する仕組み。note や Substack のようなクリエイターエコノミー機能。Phase 8 の決済基盤が整った段階で検討する。

**検討事項**
- [ ] 記事の価格設定（著者が自由に設定 / プラットフォーム手数料率の決定）
- [ ] 購入済み記事の閲覧管理テーブル設計（`blog_post_purchases`: user_id, blog_post_id, price, purchased_at）
- [ ] 記事プレビュー範囲の設定（冒頭 N 文字を無料公開、残りを有料壁で保護）
- [ ] 著者への収益分配フロー（Stripe Connect / 振込バッチ等）
- [ ] 返金ポリシーの策定
- [ ] `blog_posts.visibility` に `PAID` を追加するか、別カラム `price` で管理するかの設計判断
- [ ] 特定商取引法・資金決済法との適合確認

### 回覧テンプレート機能（Phase 8+）

定期回覧（月次安全点検・週次報告等）の雛形を保存・再利用する機能。Phase 5 の回覧板が実運用で定着し、利用パターンが明確になった後に実装する。

**検討事項**
- [ ] `circulation_templates` テーブル設計（title, body, recipient_ids JSON, circulation_mode, stamp_display_style, attachments）
- [ ] テンプレートからの回覧作成 API（`POST /circulation/from-template/{templateId}`）
- [ ] テンプレートの共有範囲（作成者のみ / スコープ内共有）
- [ ] 定期実行スケジュール（cron 式で自動回覧作成 + DRAFT 保存 → ADMIN が確認後に start）
- [ ] テンプレートの recipient_ids をロールベース（例: 全 MEMBER）にするか固定 ID リストにするかの設計判断

### ハッシュタグ基盤（Phase 9+）

タイムライン・掲示板・チャットを横断した統一的なハッシュタグ機能。Phase 4 のタイムライン設計時に検討したが、有効に機能させるには「タグ別フィード」「トレンド表示」「オートコンプリート」等の周辺機能が必要であり、単体での実装は工数に見合わない。Phase 9 のグローバル検索機能と合わせて統一タグ基盤として設計する。

**必要テーブル（Phase 9+ で設計確定）**
- `hashtags`: id, name (VARCHAR(100), UNIQUE), usage_count (INT, denormalized), created_at
- `hashtaggables`: id, hashtag_id (FK), target_type ENUM('TIMELINE_POST', 'BULLETIN_THREAD', 'CHAT_MESSAGE'), target_id, created_at — ポリモーフィック中間テーブル

**実装項目**
- [ ] ハッシュタグのパース・正規化ロジック（全角→半角、大文字→小文字統一）
- [ ] タグ別フィード API（`GET /hashtags/{name}/posts`）
- [ ] トレンドハッシュタグ API（`GET /hashtags/trending` — 直近24時間の usage_count 上位）
- [ ] オートコンプリート API（`GET /hashtags/suggest?q=xxx` — 前方一致）
- [ ] フロントエンドの `#` ハイライト表示 + リンク化
- [ ] タイムライン・掲示板・チャット横断検索との統合

### Phase 9 開始前（広告・外部連携）
- [ ] 広告 DB テーブルの追加範囲を機能設計時に確定する
  - 初期実装（Phase 9）は `affiliate_configs` 1テーブルのみ
  - 将来拡張用の `ads` / `ad_campaigns` / `ad_targeting_rules` / `ad_impressions` / `ad_clicks` の5テーブルは、ターゲティング広告プラットフォームを実装する Phase を別途決定してから Flyway マイグレーションに追加する
  - 現時点では将来テーブルのスキーマは README.md の「広告・アフィリエイト」DB設計セクションに記載済み（将来テーブルのカラム定義を確定してから追加すること）
- [ ] アフィリエイト設定（`affiliate_configs`）の初期データ（Amazonアソシエイトタグ・配置設定）を Flyway シードデータとして投入する
- [ ] 広告表示対象の判定ロジックを設計する（フリープランのみ表示・有料プランは非表示）
- [ ] SSR 時の広告描画と `GET /ads/active` API のキャッシュ戦略を設計する
- [ ] 緊急地震速報連動の安否確認自動配信の設計・実装（詳細は「緊急地震速報連動の安否確認自動配信」参照）
  - [ ] 気象庁 XML / P2P地震情報 API のデータソース選定と接続実装
  - [ ] `earthquake_alert_configs` / `earthquake_alert_logs` テーブル設計・Flyway マイグレーション
  - [ ] `teams` / `organizations` に `auto_safety_check_enabled` カラム追加（ALTER TABLE）
  - [ ] 地域マッチングロジック（都道府県 × 気象庁地域コード）の実装
  - [ ] 震度6強以上の強制配信ロジック（ON/OFF 設定を無視）の実装
  - [ ] 30分以内の重複配信防止ロジックの実装
- [ ] **Phase 9 実装完了後**: コードベース内のすべての `if (FEATURE_V9_ENABLED)` 分岐を削除し、広告・コルクボード機能を常時有効のハードコードにリファクタリングする（`FEATURE_V9_ENABLED` 環境変数も削除）
- [ ] ターゲティング広告・クリックログ（`ad_clicks` / `ad_impressions`）実装前に、プライバシー・法的対応を完了する（詳細は「広告プラットフォームの本格展開 > プライバシー・法的対応」参照）
  - [ ] 利用規約・プライバシーポリシーに広告配信目的の行動履歴利用を明記
  - [ ] オプトアウト設定 UI の設計
  - [ ] `ad_clicks` の保持期間（推奨: 90日）と削除バッチの設計
  - [ ] `ad_impressions` の保持期間（推奨: 90日）と削除バッチの設計。`ad_daily_stats` への日次集計が完了した分から順次削除し、詳細ログを無期限に積み上げない設計にする

### Phase 10-11 開始前（セキュリティ監査）
- [ ] F07 カルテ `allergy_info` カラムの AES-256 暗号化導入
  - Phase 7 では平文保存。Phase 10-11 のセキュリティ監査フェーズで暗号化を実施する
  - 対象カラム: `chart_records.allergy_info`（アレルギー・禁忌情報）
  - 暗号化方式: AES-256（アプリ層での暗号化/復号。DB 側の Transparent Data Encryption ではなく、カラム単位で暗号化）
  - 暗号鍵管理: AWS KMS または HashiCorp Vault を使用（鍵のローテーション対応）
  - マイグレーション: 既存の平文データを暗号化データに変換するデータマイグレーションスクリプトを作成
  - 検索への影響: 暗号化後は `allergy_info` の LIKE 検索が不可になるため、必要に応じて検索用ハッシュカラムの追加を検討

### Phase 10 開始前（運用最適化）
- [ ] 非アクティブアーカイブバッチの設計と実装
  - 個人ユーザー（6ヶ月）
    - [ ] 対象抽出クエリ（`last_login_at < NOW() - INTERVAL 6 MONTH AND archived_at IS NULL`）
    - [ ] アーカイブ前メール通知テンプレートの作成（日本語・英語）
    - [ ] Redis キャッシュ削除処理（SCAN + DEL）
    - [ ] S3 オブジェクト → Glacier Instant Retrieval 移行（ライフサイクルルール設定）
    - [ ] `users.archived_at` 付与 + `users.last_login_at` カラムの追加確認
    - [ ] ログイン成功時のアーカイブ解除処理（`archived_at` クリア・S3 復元トリガー）
    - [ ] フロントエンドの「アカウントを復元中...」ローディング画面の実装
  - チーム・組織（12ヶ月 / 全メンバーの最新ログインが基準）
    - [ ] 対象抽出クエリ（全メンバーの `MAX(last_login_at)` が12ヶ月前を超えたチーム/組織）
    - [ ] `teams.archived_at` / `organizations.archived_at` カラムの追加確認
    - [ ] アーカイブ中チーム/組織の検索・一覧クエリからの除外処理
    - [ ] チーム/組織の Redis キャッシュ（`team:modules:{id}` 等）削除処理
    - [ ] S3 上のチーム/組織ファイルの Glacier Instant Retrieval 移行
    - [ ] いずれかのメンバーがログインした際のチーム/組織アーカイブ解除処理
    - [ ] アーカイブ前の ADMIN へのメール通知（「12ヶ月間ログインがありません」）

### Phase 11 開始前（本番デプロイ）
- [ ] インフラ構成の最終確定
- [ ] バックアップ・災害復旧計画
- [ ] 監視・アラート設定（Prometheus / Grafana 等）
- [ ] パフォーマンステスト結果の確認
