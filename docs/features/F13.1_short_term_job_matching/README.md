# F13.1: スキマバイト（短期業務マッチング）機能

> **ステータス**: 🟡 設計完了・実装待ち
> **実装フェーズ**: Phase 13
> **最終更新**: 2026-04-21（**第三版** — JOBBER ロール新設、Jobber 総合掲示板、7 日間エスクロー、大規模募集の運営確定フロー、TODO → 求人自動転換の 5 項目を追加）
> **モジュール種別**: オプション機能 #13.1（チーム／組織向け業務委託マッチング基盤）
> **関連ドキュメント**: F01.1 認証、F01.2 組織・チーム・メンバー・ロール（**`JOBBER` ロール追加**）、F01.7 カスタム公開範囲テンプレート、F02.5 行動メモ／TODO（**TODO → Jobber 募集自動変換**）、F03.11 募集型予約、F04.2 チャット、F04.3 プッシュ通知（**Jobber 総合掲示板新着通知・エスクロー通知**）、F04.9 確認通知システム、F08.2 支払い管理・コンテンツアクセス制御、F08.6 予算・会計、F10.3 監査ログ、F11.1 PWA/オフライン（**QR チェックインのオフラインキュー統合・TODO 変換オフライン対応**）、F11.3 UI i18n、F12.3 GDPR/個人情報（**位置情報保管ポリシー連携**）、**F22.1 市（Market）**（謝礼決済 Phase 2 後半）。**注記（2026-06-02 是正）**: F13.1 の決済は現状 **enum/コメントのみで実装ゼロ**（`JobContractStatus` の MATCHED/AUTHORIZED/CAPTURED/PAID/DISPUTED は定義のみ、`JobContractService` は PaymentIntent/capture/transfer を呼ばない）。そのため F22.1 は **本機能（F13.1）の資金フロー設計図（[`03_ui_payment.md`](03_ui_payment.md) §8）を流用しつつ、市向けに独自の Stripe Connect 決済基盤を構築**する（受領者を札ごとに個人/チーム/組織から選択・案A Destination Charge + 手動キャプチャ）。「F13.1 にフック委譲」ではない。詳細は [`F22.1_market/payment/`](../F22.1_market/payment/README.md)。将来 F13.1 が本基盤を逆利用する場合は `escrow_transactions.source_kind=JOBMATCHING` を確保済

---

## 1. 概要

### 1.1 目的・背景

スキマ時間を活かして短期業務を請け負いたいメンバー／サポーター／**登録済み助っ人（JOBBER）**（以下 **Worker**）と、単発の業務を発注したいチーム管理者・個人オーガナイザー（以下 **Requester**）を、Mannschaft プラットフォーム上でマッチングさせる業務委託マッチング機能。タイミー・シェアフル等の一般公開型スキマバイトアプリと異なり、**チーム所属メンバー＋サポーター＋登録済み JOBBER 限定** で募集が閉じる「身内スコープ」を基本としつつ、**第三版で「Jobber 総合掲示板」を追加**し、全 Mannschaft ユーザーのうち Jobber 登録を済ませた人であれば外部チームの有償求人にも応募できる二層モデルへ拡張する。

**第三版の主要テーマ**:

1. **JOBBER ロール新設** — 有償前提の「登録済み助っ人」カテゴリ。SUPPORTER（無償ボランティアニュアンス）と有償前提の JOBBER を明確に分離。複数チーム登録可能。
2. **募集スコープ4種** — 既存の `TEAM_MEMBERS` / `TEAM_MEMBERS_SUPPORTERS` に加え、`JOBBER_INTERNAL`（当該チーム所属 JOBBER 限定）と `JOBBER_PUBLIC_BOARD`（Jobber 総合掲示板公開）を追加。
3. **7 日間エスクローシステム** — Stripe `capture_method=manual` を活用し、業務完了承認後 7 日間保留。異議なしで自動 capture、両者合意で早期 release。
4. **大規模募集の運営確定フロー** — 10 名以上は QR 運用が非現実的であるため、運営が業務時間を手動確定し Worker 承認するフローを追加。1〜3 名は QR 推奨、4〜9 名は選択可能、10 名以上は運営確定方式のみに UI で強制。
5. **TODO → Jobber 募集自動転換** — F02.5 TODO に「Jobber 募集」フラグを追加し、ON で自動的に求人 (`job_postings`) を生成する導線を整備。TODO 作成者の権限チェック＋モーダルでの報酬額等補完。

### 1.2 ターゲットユーザー

| 分類 | 想定ペルソナ | 代表的ユースケース |
|------|-------------|-------------------|
| Requester | スポーツチーム管理者 | 大会当日の受付係・駐車場係・写真撮影を数時間だけ依頼 |
| Requester | 町内会・PTA の ADMIN | イベント当日の設営・撤収・配布物仕分け |
| Requester | 組織 ADMIN | 単発のデータ入力・翻訳・デザイン作業 |
| Worker（MEMBER） | 学生メンバー | 空き時間に短時間の手伝いで報酬を得たい |
| Worker（SUPPORTER） | 社会人サポーター | スキルを活かして副業的に作業を受けたい（翻訳・撮影等） |
| Worker（MEMBER） | 高齢メンバー | 体を動かせる軽作業で収入を得たい |
| **Worker（JOBBER）** | **チーム外の登録済み助っ人** | **複数チームから招待を受け、大規模イベント・繁忙期にピンポイントで応援に入る** |
| **Worker（Jobber 総合掲示板経由）** | **Mannschaft 全ユーザーのうち Jobber 登録済みの人** | **勤務地・給与・スキル・期間で絞り込み、興味のある外部チームの求人へ応募** |

### 1.3 ビジネスモデル

- **資金は Mannschaft で保有しない**。Stripe Connect（Express アカウント）＋ Destination Charges を使い、資金決済法上のライセンサーは Stripe。Mannschaft は業務委託契約の成立・マッチング・評価の場を提供するだけの仲介プラットフォーム。
- プラットフォーム手数料（Requester 側）＋ ワーカー手数料（Worker 側）の両面課金で収益を得る。
- 日本国内ユーザーを想定（通貨 JPY 固定、源泉徴収は原則なし）。

### 1.4 既存機能との関係

| 既存機能 | 関連内容 |
|---|---|
| F01.2 組織・チーム・メンバー・ロール | `memberships.role_kind` に **`JOBBER`** 追加検討（F00.5 範囲外。`MEMBER`/`SUPPORTER` と区別が必要な場合は別途 memberships 拡張）、`MANAGE_JOBS` 権限の詳細化 |
| F01.7 カスタム公開範囲テンプレート | 求人の `visibility` として `CUSTOM_TEMPLATE` を許可 |
| **F02.5 行動メモ／TODO** | **`todos.job_posting_id` 追加 + 「Jobber 募集」フラグで求人自動生成** |
| F03.11 募集型予約 | 「募集→申込→確定」フローを踏襲。ただし **決済**・**契約成立**・**評価** を追加した上位互換 |
| F04.2 チャット | Requester⇔Worker の業務中コミュニケーションは既存1対1チャットを再利用 |
| F04.3 プッシュ通知 | 応募通知・契約成立通知・完了承認通知・支払い通知・**Jobber 総合掲示板新着通知・エスクロー期間通知** は既存 `notifications` に委譲 |
| F04.9 確認通知システム | Requester が Worker に「確認して下さい」を送る用途で流用可能（任意）+ **Jobber 招待受諾・運営側時間確定の承認** で流用 |
| F08.2 支払い管理 | Stripe Customer 管理（`stripe_customers`）は共通。Connect 側は新規テーブル |
| F10.3 監査ログ | 金銭移動・**JOBBER 招待／受諾・エスクロー release・TODO 変換** は全件 `audit_logs` に記録 |
| **F11.1 PWA/オフライン** | **TODO → Jobber 変換はオフライン中は下書き保存、オンライン復帰で求人作成 API を呼ぶ**（既存 `offlineQueue` に `todoToJobConversion` タイプ追加） |

---

## 2. 機能要件

### 2.1 求人投稿（Requester）

- ADMIN / DEPUTY_ADMIN（`MANAGE_JOBS` 権限保持者） / 個人オーガナイザー（将来拡張）が求人を投稿
- 必須入力: タイトル・業務内容・業務日時（開始／終了）・場所（住所 or オンライン）・基本報酬額・募集人数・応募締切・**業務時間確定方式（QR or 運営確定）**
- 任意入力: カテゴリ・必要スキル／資格・持ち物・服装・業務完了条件・交通費支給有無・公開範囲
- 下書き保存・公開予約（`publish_at` 未来日時を指定）
- 公開後の編集は限定的（報酬額・日時の変更は応募発生後は不可、それ以外はOK）
- 公開範囲（`visibility_scope`）: **第三版で 4 種に整理**（旧 `visibility` カラムからリネーム）
  - `TEAM_MEMBERS` — 当該チームメンバー限定
  - `TEAM_MEMBERS_SUPPORTERS` — メンバー＋サポーター
  - **`JOBBER_INTERNAL`** — 当該チーム登録済み **JOBBER 限定**（第三版新規）
  - **`JOBBER_PUBLIC_BOARD`** — Mannschaft 全ユーザーのうち **Jobber 登録済み** の人向け、総合掲示板へ掲載（第三版新規）
  - 加えて従来互換: `ORGANIZATION_SCOPE` / `CUSTOM_TEMPLATE` も引き続き選択可能
  - **注: `PUBLIC`（不特定多数、認証不要）は労働者派遣法・税務コンプライアンスの観点から禁止**。`JOBBER_PUBLIC_BOARD` はあくまで **認証済み＋ Jobber プロフィール登録済み** のユーザーに閉じた範囲であり、不特定多数公開ではない
- **業務時間確定方式（`time_confirmation_method`）**: 第三版新規
  - `QR_CHECKIN` — QR チェックイン／アウト方式（§2.3.1）。1〜9 名の小規模募集向け
  - `ORG_CONFIRM` — 運営側が業務時間を手動確定し、Worker が承認する方式（§2.9.3）。10 名以上の大規模募集向け
  - 募集人数別の強制ルールは §2.9 を参照

### 2.2 応募・マッチング（Worker）

- Worker（MEMBER / SUPPORTER）が求人一覧から検索・応募
- 応募時に自己 PR（任意、500文字まで）を添付
- 応募締切前であれば応募キャンセル可能
- Requester が応募者一覧から **採用** を決定 → 契約成立（`job_contracts.status = MATCHED`）
- 定員充足すると自動的にクローズ（以後の応募は不可）
- **同時応募時の排他制御**: 採用確定時に DB トランザクション内で `SELECT ... FOR UPDATE` による行ロック。加えて `PostgreSQL advisory lock` / `MySQL GET_LOCK` で job_posting 単位の排他を二重化
- Worker がまだ Stripe Connect Express オンボーディングを完了していない場合、**採用確定前に Worker 側にオンボーディング完了を促す**。オンボーディング未完了で一定期間（72時間）経過した場合は採用を自動キャンセル

### 2.3 業務中コミュニケーション

- 契約成立（`MATCHED`）時点で Requester ⇔ Worker 間に自動で 1 対 1 チャットルームを開設（F04.2 既存機能）
- チャット内で待ち合わせ場所・時間変更・緊急連絡を交換
- 業務完了承認後 90 日間はチャット閲覧可能、以降は論理削除し、ログは監査ログのみ残す
- **個人連絡先（電話番号・LINE等）の直接交換はガイドラインで禁止**（F04.5 モデレーション：自動検出 + 報告）

### 2.3.1 QR コードチェックイン／チェックアウト

- **業務開始時（チェックイン）**: Requester 側デバイスが **チェックイン用 QR コード** を画面表示 → Worker が自分のスマホで読み取って `CHECKED_IN` に遷移
- **業務終了時（チェックアウト）**: Requester 側デバイスが **チェックアウト用 QR コード** を画面表示 → Worker が読み取って `CHECKED_OUT` に遷移
- **QR トークンの設計（リプレイ攻撃対策）**:
  - ペイロード: `contract_id` / `worker_user_id`（採用確定 Worker 以外が読んでも検証失敗）/ `type`（`IN`/`OUT`）/ `nonce`（UUIDv4）/ `issued_at` / `expires_at`
  - 署名: HMAC-SHA256（鍵は環境変数 `JOB_QR_SIGNING_SECRET`、ローテーション対応 `kid` 付き JWT 互換フォーマット）
  - **TTL 60 秒**（デフォルト、設定上限 5 分）。`expires_at` 経過後は検証失敗
  - `nonce` は `job_qr_tokens` に INSERT、一度使った `nonce` は `used_at` を記録し**同一トークンの再スキャン不可**（DB UNIQUE 制約）
  - Requester 画面では 30 秒ごとに自動再発行（QR の見た目が変わる）→ カメラ越しに撮影された古いスクリーンショットで不正チェックインを防止
- **Geolocation（補助記録）**:
  - Worker スキャン時にブラウザ Geolocation API で位置情報を取得（HTTPS 必須、ユーザー同意必須）
  - 業務場所（`job_postings.location_latitude/longitude`）から **500 m 以上乖離** した場合は `job_check_ins.geo_anomaly=TRUE` を立て、Requester にアラート通知。自動拒否はしない（GPS 精度問題の誤検出回避）
  - 位置情報は暗号化保存（アプリ層で AES-256-GCM、鍵は KMS 相当に保管）。参照可能なのは Requester 本人・同一チーム ADMIN（`MANAGE_JOBS`）・SYSTEM_ADMIN のみ
  - 保管期間: 契約完了後 **90 日**で自動削除（`job_check_ins.geolocation_*` カラムを NULL 更新、`geolocation_deleted_at` 記録）
- **オフライン時のフォールバック（F11.1 PWA 連携）**:
  - 電波なし環境で Worker がスキャンした場合、QR トークン生データ + スキャン時刻 + Geolocation を IndexedDB `offlineQueue` に一時保存
  - オンライン復帰で `POST /api/v1/jobs/check-ins` にリプレイ送信。Server 側は `expires_at` 超過していても **スキャン時刻** が有効範囲内であれば受け付ける（`offline_submitted=TRUE` を記録）
  - 手動入力フォールバック: QR 読取に失敗した場合、Requester が 6 桁の短命コード（QR と同じトークンから派生、TTL 60秒）を口頭で Worker に伝え、Worker が画面入力しても同等に検証可能
- **業務時間の自動計算**: `work_duration_minutes = CHECKED_OUT.scanned_at − CHECKED_IN.scanned_at`（将来、時給制プラン導入時に報酬自動算出の根拠として利用）
- **不正防止・アラート**:
  - `CHECKED_IN` から契約上の業務終了時刻（`work_end_at`）+ 2 時間経ってもチェックアウトがない場合、Requester・Worker 双方にアラート通知（`JOB_CHECKOUT_MISSING`）
  - 同一 Worker が同時刻に別契約でチェックインしている場合は拒否（掛け持ち禁止、400 応答）

### 2.4 業務完了承認・評価

- Worker がチェックアウト（`CHECKED_OUT`）後に「完了報告」を送信（`CHECKED_OUT` → `COMPLETION_REPORTED` への遷移リクエスト）
- Requester が完了報告を受けて **承認（ACCEPT）** または **差し戻し（REJECT）**
- 承認で `COMPLETED` に確定し、決済が実行される（Capture / Transfer）
- 差し戻しは理由必須、3回差し戻しまで。それ以降は **紛争モード** に移行（§11.5）
- Requester が 7 日間放置した場合、自動承認（`auto_accepted_at` を記録し `COMPLETED` 確定）
- **評価は内部記録（Public 化なし）**:
  - 評価 `job_reviews` は「内部メモ」扱いとし、**他チームや外部ユーザーに公開しない**
  - 閲覧可能なのは: (1) **同一チームの ADMIN・DEPUTY_ADMIN（`MANAGE_JOBS` 権限保持者）**、(2) **評価を受けた Worker 本人**（ただし自チーム ADMIN のコメントが自分宛てに書かれたものに限る）
  - 星評価の平均値表示・公開レーティング・プロフィール表示は**実装しない**（信頼はチーム内コミュニティが既に担保しているため）
  - 目的: チーム運営の質を上げるための内部記録（次回起用判断・改善フィードバック）

### 2.5 Stripe Connect オンボーディング

- Worker が初回採用確定時に Express アカウント作成（account_links hosted onboarding）
- 本人確認（KYC）・銀行口座登録は Stripe 側で完結（Mannschaft は最低限のID `acct_xxx` のみ保持）
- オンボーディング完了 Webhook（`account.updated` → `charges_enabled=true` ＆ `payouts_enabled=true`）受信でアプリ側ステータスを `READY` に更新
- オンボーディング未完了 Worker は応募は可能だが、採用確定のためには完了が必要

### 2.6 決済・精算フロー

- Requester が採用確定と同時に PaymentIntent を作成（`capture_method=manual` で事前オーソリ）
- 業務完了承認で capture 実行 → 同じ PaymentIntent で `transfer_data.destination` 経由で Worker の Express へ送金
- `application_fee_amount` には Requester 手数料 + Worker 手数料相当分（ただし Stripe 決済手数料を除く）を設定
- Worker の実際の受取額は PaymentIntent の capture 後に Stripe が自動で Worker アカウントへ入金
- Payout スケジュール: Stripe 日本のデフォルト（週次）。Instant Payouts を将来オプション提供

### 2.7 通知システム連携（F04.3）

| イベント | 宛先 | 種別 | 強制配信 |
|---------|------|-----|---------|
| 新規求人公開 | 公開範囲内のWorker候補 | JOB_POSTED | opt-in |
| 応募あり | Requester | JOB_APPLIED | opt-in |
| 採用確定 | Worker | JOB_MATCHED | 強制（業務契約成立のため） |
| 業務開始リマインド | 双方 | JOB_REMINDER | opt-in |
| チェックイン成立 | Requester | JOB_CHECKED_IN | opt-in |
| チェックアウト成立 | Requester | JOB_CHECKED_OUT | opt-in |
| チェックアウト未実施警告 | 双方 | JOB_CHECKOUT_MISSING | 強制（業務時間未確定のため） |
| Geolocation 乖離警告 | Requester | JOB_GEO_ANOMALY | 強制（不正検知のため） |
| 完了報告受信 | Requester | JOB_COMPLETION_REPORTED | 強制 |
| 承認／差し戻し | Worker | JOB_APPROVED / JOB_REJECTED | 強制 |
| 支払い完了 | Worker | JOB_PAID | 強制 |
| 内部評価メモ受領（Worker側は自分宛てのみ） | Worker／同一チーム ADMIN | JOB_REVIEW_LOGGED | opt-in |
| Stripe 決済失敗 | Requester | JOB_PAYMENT_FAILED | 強制 |
| Payout 失敗 | Worker | JOB_PAYOUT_FAILED | 強制 |
| **=== 第三版追加 ===** | | | |
| JOBBER 招待受信 | 招待対象ユーザー | JOB_JOBBER_INVITATION | 強制 |
| JOBBER 招待受諾 | 招待者（ADMIN） | JOB_JOBBER_INVITATION_ACCEPTED | opt-in |
| JOBBER 招待辞退 | 招待者（ADMIN） | JOB_JOBBER_INVITATION_DECLINED | opt-in |
| 総合掲示板新着マッチ | 条件マッチ Jobber | JOB_PUBLIC_BOARD_MATCH | opt-in（条件フィルタで制御）|
| 運営業務時間確定依頼 | Worker | JOB_TIME_CONFIRMATION_REQUESTED | 強制（24h/48h/60h 多段階リマインダー付）|
| Worker 時間承認完了 | Requester | JOB_TIME_CONFIRMED_BY_WORKER | opt-in |
| Worker 時間異議提起 | Requester・ADMIN | JOB_TIME_CONFIRMATION_DISPUTED | 強制 |
| エスクロー開始（完了承認時）| Worker | JOB_ESCROW_STARTED | 強制 |
| エスクロー残り 24 時間リマインド | Worker | JOB_ESCROW_ENDING_SOON | opt-in |
| 早期 release 完了 | 両者 | JOB_EARLY_RELEASE_COMPLETED | 強制 |
| エスクロー自動 capture 完了 | 両者 | JOB_ESCROW_CAPTURED | 強制 |
| 異議申立発生 | 相手方・ADMIN | JOB_ESCROW_DISPUTED | 強制 |
| Stripe 再オーソリ要求 | Requester | JOB_REAUTHORIZATION_REQUIRED | 強制 |
| TODO → 求人変換完了 | TODO 作成者 | JOB_TODO_CONVERTED | opt-in |

### 2.8 履歴管理・再応募時の過去履歴参照

#### 2.8.1 チーム視点の履歴ダッシュボード

- 画面パス: **`/teams/{teamId}/jobs/history`**
- 表示項目:
  - 募集日時 / 募集タイトル / 依頼内容（概要）/ 受注 Worker（氏名・アイコン）/ 業務時間（チェックイン〜アウト実測）/ 支払総額（税込）/ ステータス / 内部評価メモ（ある場合）
- フィルタ: 期間（From/To）/ Worker（ID 選択）/ ステータス（MATCHED / COMPLETED / CANCELLED / DISPUTED 等）/ 金額レンジ（min/max JPY）/ カテゴリ
- ソート: 募集日時 DESC（デフォルト）/ 金額 DESC / 業務時間 DESC
- **CSV エクスポート**: フィルタ条件下の全件を CSV（UTF-8 BOM 付き、Excel 互換）でダウンロード
- ページング: 50 件/ページ、Cursor ベース
- アクセス権限: **当該チーム ADMIN / DEPUTY_ADMIN（`MANAGE_JOBS`）** のみ

#### 2.8.2 再応募時の過去履歴パネル（Requester 側）

- 募集詳細 / 応募者一覧画面のサイドに「**このWorkerの過去依頼履歴**」パネルを表示
- 表示条件: 応募 Worker が同一チーム（または同一組織配下のチーム）で過去に契約履歴がある場合のみ
- 表示内容:
  - 過去契約回数（completed 件数）
  - 総業務時間（時間単位、小数 1 桁）
  - 総支払額（税込、JPY）
  - 前回業務日（`completed_at` の最新）
  - 前回内部評価メモ（直近の `job_reviews.comment`、100 字まで要約）
  - 直近 3 件の業務タイトル（クリックで契約詳細へ）
- 権限: **当該チーム ADMIN / DEPUTY_ADMIN（`MANAGE_JOBS`）+ 募集投稿者本人**（DEPUTY_ADMIN の場合は `MANAGE_JOBS` 必須）

#### 2.8.3 Worker マイページ履歴（プライベート、自分専用）

- 画面パス: **`/me/jobs/history`**
- Worker 本人のみアクセス可。**他人には表示しない**（チーム ADMIN もこの画面は見ない）
- 表示項目: 募集タイトル / チーム名 / 業務日 / 業務時間 / 受取額 / ステータス
- フィルタ: 期間 / チーム / ステータス
- **目的**: Worker が過去の経験を振り返り、次の応募に活かす

#### 2.8.4 チーム切替時の履歴分離（マルチチーム Worker）

- Worker が複数チームに所属している場合、各チームは**自チーム経由の契約履歴のみ**参照可能
- 別チームでの Worker 評価・業務履歴は **絶対に漏出させない**（§2.4 の非公開方針と整合）
- 組織スコープの履歴は、「同一組織配下の ADMIN」のみが組織全体の履歴を見られる（`/organizations/{orgId}/jobs/history`、Phase 13.2 拡張で検討）

#### 2.8.5 集計ビュー

- 集計用ビュー `v_worker_team_history`（初版はアプリ側 SELECT 集計で可、性能問題が出たらマテリアライズド化を検討）
- カラム案: `worker_user_id`, `team_id`, `total_contracts`, `total_work_minutes`, `total_paid_jpy`, `last_contract_at`, `last_review_comment_preview`

### 2.9 JOBBER ロール（第三版新規）

#### 2.9.1 JOBBER ロールの定義

**JOBBER** は、チーム独自に登録する「有償前提の助っ人」カテゴリ。SUPPORTER（無償ボランティアニュアンス）と明確に分離する。

| 項目 | SUPPORTER | JOBBER（第三版新規） |
|---|---|---|
| 想定ニュアンス | 無償のボランティア協力者（PTA 役員、保護者、OB/OG 等） | **有償前提の助っ人** — 報酬を得ることを主目的にチームを手伝う |
| チーム所属性 | チームに紐付き、通常はそのチームの活動（タイムライン・TODO・投稿）に自由に参加 | チームに紐付くが、**スキマバイト関連機能にのみアクセス可能**（タイムライン通常投稿・TODO・議事録等は制限） |
| 複数チーム所属 | 複数チームに可 | **複数チームに同時登録可能**（各チーム独立） |
| 登録フロー | ADMIN からの招待 → 本人受諾 | ADMIN / DEPUTY(`MANAGE_JOBS`) からの招待 → 本人受諾（F04.9 確認通知システムで受諾フロー実装） |
| 招待時必須項目 | メールアドレス or 既存ユーザー ID | メールアドレス or 既存ユーザー ID + **推定時給帯（任意）** + **想定業務カテゴリ（任意）** |
| 求人応募範囲 | `TEAM_MEMBERS_SUPPORTERS` までの募集に応募可 | **`JOBBER_INTERNAL` の募集に応募可**。加えて自分の Jobber プロフィールが公開 ON なら `JOBBER_PUBLIC_BOARD` 経由の外部求人にも応募可 |

#### 2.9.2 JOBBER 登録フロー

```
(A) 既存 Mannschaft ユーザーを JOBBER として招待する場合:
  1. ADMIN / DEPUTY(MANAGE_JOBS) が /teams/{teamId}/jobbers/invite で ユーザーを選択 + 推定時給帯 / 想定カテゴリ を任意入力
  2. 招待対象ユーザーに F04.9 確認通知として「[チーム名] から JOBBER 招待が届きました（有償前提の助っ人登録です）」を送信
  3. 招待された側が受諾 → memberships に (user_id, scope_type='TEAM', scope_id=teamId, role_kind='MEMBER', joined_at=NOW()) を INSERT（JOBBER は memberships.role_kind='MEMBER' として管理し、jobber_profiles でジョブ固有情報を保持）
  4. 受諾者に「jobber_profiles」がなければ自動作成（空プロフィール）、ある場合は既存レコード流用
  5. チーム側通知: 「{ユーザー名} が JOBBER として加入しました」

(B) 外部の未登録者（メールアドレスのみ）を招待する場合:
  1. ADMIN がメールアドレスを入力して招待送信
  2. 招待メールから Mannschaft アカウント作成フローに誘導 → 作成完了後に JOBBER 登録フローへ戻る
  3. 以降は (A) と同じ
```

- 招待トークンの TTL: **72 時間**（期限切れは再送可）
- 招待拒否時: `memberships` には INSERT されず、監査ログに `JOB_JOBBER_INVITATION_DECLINED` を記録
- JOBBER 本人はいつでも自分から `DELETE /api/v1/teams/{teamId}/jobbers/me` で離脱可能

#### 2.9.3 JOBBER の権限制限

JOBBER は**スキマバイト関連のみ**チームにアクセス可能。以下は明示的に制限される:

| 機能 | MEMBER | SUPPORTER | **JOBBER** |
|---|---|---|---|
| チームタイムライン閲覧 | ◎ | ◎ | **×（スキマバイト関連投稿を除く）** |
| チームタイムライン投稿 | ◎ | △（ADMIN 設定次第）| **×** |
| チーム TODO 閲覧 | ◎ | △ | **×（自分に割り当てられた Jobber 募集由来 TODO を除く）** |
| チーム議事録閲覧 | ◎ | △ | **×** |
| チームチャット（F04.2 全体チャット） | ◎ | △ | **×** |
| スキマバイト求人一覧（`JOBBER_INTERNAL` 範囲）| × or △ | × or △ | **◎** |
| 自分が応募 / 受注した求人の詳細・チャット | ◎ | ◎ | **◎** |
| 自分の Jobber プロフィール編集（`jobber_profiles`）| — | — | **◎** |

- ポリシー実装: `TeamRolePolicy.canAccessTeamFeature(role, feature)` で機能ごとに判定。`JOBBER` の場合はホワイトリスト方式（明示的に許可された機能のみ）
- UI 側ガード: JOBBER ロールでログインしているユーザーに対してはナビゲーションメニューからタイムライン・TODO・議事録を非表示

### 2.10 Jobber 総合掲示板（第三版新規）

#### 2.10.1 総合掲示板の位置付け

- 画面パス: **`/jobs/public-board`**
- 対象: 全 Mannschaft ユーザーのうち、`jobber_profiles.is_public_board_opt_in = TRUE` を ON にしたユーザー
- 掲載される求人: `job_postings.visibility_scope = 'JOBBER_PUBLIC_BOARD'` の求人すべて
- **外部チームの JOBBER への発見 UX** を提供する場。既存所属チーム以外の求人にも応募が可能
- 応募時点で当該チームの `memberships` に MEMBER として自動加入（JOIN-ON-APPLY 方式）するか、応募のみで加入しない（APPLY-ONLY 方式）かは **Requester 側が求人作成時に選択**（`job_postings.auto_join_jobber_on_apply` BOOLEAN フィールド）

#### 2.10.2 絞り込み検索

Jobber 総合掲示板のフィルタ UI:

| フィルタ項目 | 型 | 備考 |
|---|---|---|
| 勤務地（半径 km） | 現在地 or 住所 + 半径 5/10/25/50/100 km | 未入力時は全件 |
| 報酬額レンジ | min / max JPY | 時給換算表示オプション |
| 必要スキル | チェックボックス（多言語・調理・運転・撮影 等） | `jobber_profiles.preferred_skills` と AND マッチ |
| 期間 | 日付 From / To | `work_start_at` 範囲 |
| カテゴリ | RECEPTION / PHOTO / 等 | 複数選択可 |
| 時間帯 | 早朝 / 午前 / 午後 / 夜 | 業務開始時刻ベース |
| 募集方式 | QR / 運営確定 | `time_confirmation_method` で絞り込み |
| オンライン可 | BOOLEAN | `location_type='ONLINE'` or `HYBRID` |

#### 2.10.3 新着通知設定

- Jobber プロフィールに「**興味のある条件**」を保存（`jobber_profiles.notification_filters` JSON）
  - 例: `{ "max_distance_km": 25, "min_reward_jpy": 3000, "categories": ["PHOTO","TRANSLATION"] }`
- バックエンドの `JobberPublicBoardNotifier`（@Scheduled 15 分間隔）が新規掲載求人を取得し、条件マッチユーザーへプッシュ通知 `JOB_PUBLIC_BOARD_MATCH` を送信
- 通知の opt-out は `job_notification_preferences.public_board_enabled = FALSE` で停止

#### 2.10.4 個人情報露出範囲

- 総合掲示板の求人カードに表示される Requester 情報: **チーム名（公開名）** のみ
- Requester 個人の氏名・連絡先は**非公開**（応募・採用後の契約詳細でのみ表示）
- 応募者側（Jobber）の情報は、Requester 側には **氏名・アバター・Jobber プロフィール公開範囲に含まれるスキル** のみ見える
- 住所・電話番号は契約成立後にチャット経由での相互同意で交換（§11.3 と同じモデレーションルール）

### 2.11 7 日間エスクローシステム（第三版新規）

#### 2.11.1 エスクローの流れ

```
業務完了承認（§2.4）
  ↓
Stripe capture_method=manual のまま、application 側は「業務完了承認済」状態を記録
  ↓
7 日間の異議申立期間（dispute_window_ends_at = approved_at + 7日）
  ├─ 異議なし → 7 日経過で自動 capture → Worker payout
  ├─ Worker・Requester 両者合意 → 早期 release（両者ボタン押下で即 capture）
  └─ Requester 異議 / Worker 異議 → DISPUTED 状態へ → ADMIN 仲裁
```

- `job_payments.escrow_status` ENUM を新設: `HOLDING`（エスクロー中）/ `RELEASED`（capture 完了）/ `DISPUTED`（異議提起）/ `CANCELLED`（オーソリ失効）
- `job_payments.captured_at` は「実際に capture した時刻」、`dispute_window_ends_at` は「7 日タイマーの終了予定時刻」を表す
- **Stripe `capture_method=manual` の制約**: Stripe の標準 authorization hold 期間は 7 日（カード種別により異なる、最短 2 日・最長 7 日）。7 日を超えて capture すると authorization 切れで再決済が必要になる。Mannschaft の「7 日エスクロー」は Stripe 標準の hold 期間上限とちょうど合うため、**期間拡張は不可**（要件として固定）
- **7 日目の境界問題**: Stripe authorization は card-issuer 側で最大 7 日。Mannschaft 側は安全マージンとして **6 日 22 時間**の時点で capture バッチを走らせ、ギリギリ 7 日目で取り損ねる事故を回避

#### 2.11.2 早期 release（両者合意）

- 完了承認後、Worker にも Requester にも「✅ 早期に報酬を確定する」ボタンが表示される
- 両者が押下した時点で `POST /api/v1/job-payments/{id}/early-release` が叩かれる
- サーバー処理:
  1. `job_payments.escrow_status = 'HOLDING'` を確認
  2. 両者ボタン押下のフラグ (`early_release_requester_approved_at` / `early_release_worker_approved_at`) が**両方 NOT NULL** であることを検証
  3. Stripe PaymentIntent capture 実行
  4. `escrow_status = 'RELEASED'`, `captured_at = NOW()` 更新
  5. 両者へ `JOB_EARLY_RELEASE_COMPLETED` 通知

#### 2.11.3 異議申立（dispute）

- Worker / Requester どちらからも 7 日以内に `POST /api/v1/job-payments/{id}/dispute` 可能
- 申立理由: 必須 300 字以内
- サーバー処理:
  1. `escrow_status = 'DISPUTED'` に更新
  2. `job_dispute_cases` に新規レコード作成（既存 §5 のテーブル流用、第三版で `escrow_payment_id` FK 追加）
  3. 自動 capture バッチの対象外となる（7 日経過しても自動 capture しない）
  4. **重要**: Stripe authorization は 7 日超過で失効するため、DISPUTED 中でも **authorization 有効期間 6 日 22 時間経過時点で一旦 capture** + その後の仲裁結果で `refunds.create()` を実行する「先 capture 後返金」方式を採用する（§8.9）

#### 2.11.4 自動 capture バッチ

- `EscrowAutoCaptureJob`（@Scheduled 10 分間隔）:
  - 対象: `escrow_status = 'HOLDING'` AND `dispute_window_ends_at <= NOW()` AND `early_release_*` 未押下
  - 処理: Stripe PaymentIntent capture → `escrow_status = 'RELEASED'`, `captured_at = NOW()`
  - 異議申立中（`DISPUTED`）は対象外
  - **失敗リトライ**: Stripe API 障害で capture に失敗したら 1 / 2 / 4 分の指数バックオフで最大 3 回リトライ。それでも失敗したら SYSTEM_ADMIN アラート

#### 2.11.5 Worker 側「預かり中」バッジ表示

- Worker の契約詳細画面 (`/contracts/{id}`) に、`escrow_status = 'HOLDING'` 中は **「📦 エスクロー預かり中（あと X 日 Y 時間）」** バッジを表示
- `dispute_window_ends_at` からのカウントダウン
- 早期 release ボタンと「異議がある場合」リンクが併記される

### 2.12 大規模募集での業務時間確定フロー（第三版新規）

#### 2.12.1 募集人数別の方式選択

| 募集人数 | QR 方式（`QR_CHECKIN`） | 運営確定方式（`ORG_CONFIRM`） |
|---|---|---|
| **1〜3 名** | ◎ 推奨（デフォルト） | △ 選択可 |
| **4〜9 名** | ○ 選択可 | ○ 選択可 |
| **10 名以上** | × 不可（UI で強制的に ORG_CONFIRM） | ◎ 必須 |

- `job_postings.time_confirmation_method` と `job_postings.use_qr_check_in` の 2 カラムで管理
  - `use_qr_check_in=TRUE` かつ `time_confirmation_method='QR_CHECKIN'`: QR 方式
  - `use_qr_check_in=FALSE` かつ `time_confirmation_method='ORG_CONFIRM'`: 運営確定方式
- UI 側で募集人数が 10 以上なら `use_qr_check_in` チェックを disable + ツールチップ「大規模募集では運営確定方式が必須です」

#### 2.12.2 運営確定方式のフロー

```
業務当日（チェックイン／アウトの代わりに運営側が手動確定）
  ↓
Requester / ADMIN が業務終了後に各 Worker の業務時間を入力
  - POST /api/v1/job-contracts/{id}/time-confirmations
  - { "work_start_at": "...", "work_end_at": "...", "break_minutes": 60, "note": "駐車場係として勤務" }
  ↓
Worker に「⏱ 業務時間の承認依頼」通知 (`JOB_TIME_CONFIRMATION_REQUESTED`, 強制配信)
  ↓
Worker が承認 or 異議提起（72 時間以内）
  - 承認: POST /api/v1/job-contracts/{id}/time-confirmations/{confId}/approve
  - 異議: POST /api/v1/job-contracts/{id}/time-confirmations/{confId}/dispute
  ↓
承認で job_contracts.status = CHECKED_OUT + TIME_CONFIRMED 遷移、決済に移行
  ↓
72 時間無反応なら自動承認（auto_approved_at 記録、§2.12.4 参照）
```

#### 2.12.3 運営側の一括入力 UI

- 複数 Worker の業務時間を **CSV アップロード** or **表形式一括入力** で送れる UI を提供
- 表形式の項目: Worker 氏名 / 開始時刻 / 終了時刻 / 休憩分 / 備考
- バリデーション:
  - `work_start_at < work_end_at`
  - 契約上の `job_postings.work_start_at/work_end_at` から大きく外れている場合（± 4 時間超）は警告
  - Worker ごとに **1 件のみ `time_confirmations`** を登録可（差し戻し後の再登録は上書きではなく version 更新で履歴保持）

#### 2.12.4 Worker 承認フロー

- Worker 側の通知 UI: 「運営から業務時間確定の通知が来ました。時間: X 時間 Y 分、金額: Z 円。問題なければ承認してください」
- **72 時間タイムアウト**: `JobTimeConfirmationAutoApprovalJob`（@Scheduled 1 時間間隔）が 72 時間無反応の `time_confirmations` を自動承認（`auto_approved_at` 記録）
- **異議提起時**: `job_time_confirmations.status = 'DISPUTED'` に更新 → ADMIN 仲裁フロー（既存 `job_dispute_cases` の派生ケース）
- **承認後の決済**: 承認 → `job_contracts.status = CHECKED_OUT` + `work_duration_minutes = (work_end_at - work_start_at - break_minutes)` → Worker 完了報告 → Requester 承認 → エスクロー（§2.11）

#### 2.12.5 QR 方式との排他性

- `job_postings` 作成時に `time_confirmation_method` を選択し、公開後は変更不可
- 応募 / 契約成立後に方式変更したい場合は、新規求人を再作成するか運営に相談（手動調整）
- `job_qr_tokens` と `job_time_confirmations` は同一契約には**どちらか一方のみ**存在する（アプリ層バリデーション）

### 2.13 TODO → Jobber 募集自動転換（第三版新規）

#### 2.13.1 TODO 側への「Jobber 募集」フラグ追加

- 既存 TODO 機能（F02.5）に **`is_jobber_recruiting` BOOLEAN** フラグを追加
- フラグ ON 条件:
  - TODO 作成者 (`created_by`) が当該チームの **ADMIN / DEPUTY(`MANAGE_JOBS`)** であること
  - 該当しないロールのユーザーには UI 上もフラグ切替ボタンを表示しない（`TeamRolePolicy.canCreateJobberRecruitingTodo` で判定）
- フラグ ON に切り替えたら、自動で「Jobber 募集モーダル」が開く（§2.13.2）

#### 2.13.2 Jobber 募集モーダル

モーダル内で以下を入力し、投稿確定で求人 (`job_postings`) を生成:

| 項目 | 自動補完 | 補完不可の場合 |
|---|---|---|
| title | TODO `title` | 編集可 |
| description | TODO `description` | 編集可 |
| work_start_at | TODO `due_date` | 編集必須（時刻未定の場合） |
| work_end_at | — | **手動入力必須** |
| base_reward_jpy | — | **手動入力必須**（500 ≤ x ≤ 1,000,000） |
| capacity | デフォルト 1 | 編集可 |
| visibility_scope | デフォルト `JOBBER_INTERNAL` | `TEAM_MEMBERS` / `TEAM_MEMBERS_SUPPORTERS` / `JOBBER_PUBLIC_BOARD` から選択可 |
| time_confirmation_method | `capacity <= 3` なら `QR_CHECKIN`、`>= 10` なら `ORG_CONFIRM` 自動、4〜9 は選択 | 編集可 |
| category | デフォルト `OTHER` | 編集可 |
| location_type | デフォルト `ONSITE` | 編集可 |
| location_address | — | 編集可 |

- 確定ボタン押下で `POST /api/v1/todos/{id}/convert-to-job-posting` が発火
- サーバー側:
  1. 権限チェック（TODO 作成者 = 実行者 = ADMIN or DEPUTY with `MANAGE_JOBS`）
  2. `job_postings` INSERT（上記内容）
  3. `todos.job_posting_id = <new id>` で逆参照更新
  4. `todos.is_jobber_recruiting = TRUE` をセット
  5. 監査ログ `JOB_TODO_CONVERTED_TO_POSTING` 記録
  6. 通知配信: scope に応じて `JOB_POSTED` / `JOB_PUBLIC_BOARD_MATCH`

#### 2.13.3 配信先（視界）

- `visibility_scope = TEAM_MEMBERS / TEAM_MEMBERS_SUPPORTERS / JOBBER_INTERNAL`: 通常の求人通知配信
- `visibility_scope = JOBBER_PUBLIC_BOARD`: **Jobber 総合掲示板のタイムライン**（`/jobs/public-board`）に自動掲載 + 条件マッチする Jobber への新着通知
- 配信はバックエンド側で統一的に処理（`JobPostingVisibilityDispatcher`）

#### 2.13.4 TODO 削除・求人削除の連動

| 操作 | 連動動作 |
|---|---|
| TODO 削除（応募者ゼロの求人） | 求人も論理削除（`job_postings.deleted_at`）、関連 `job_applications` があれば 0 件のみ許可 |
| TODO 削除（応募あり） | **警告モーダル**: 「応募者が X 名います。求人を先にキャンセルしてください」→ ブロック |
| 求人削除（`DELETE /api/v1/jobs/{id}`） | `todos.job_posting_id = NULL` に更新、`todos.is_jobber_recruiting = FALSE` にリセット |
| 求人 CANCELLED 化 | TODO 側は保持、`todos.is_jobber_recruiting = FALSE` にリセット（将来再募集可能） |

#### 2.13.5 未完成項目（報酬額未入力等）の扱い

- モーダルで必須項目（`work_end_at`, `base_reward_jpy`）が未入力だと「求人生成」ボタンを disable
- 任意項目が未入力でも最低限の情報で求人生成は可能
- 求人生成後も通常の `PATCH /api/v1/jobs/{id}` で編集可能（応募前に限る）

#### 2.13.6 TODO 一覧 UI のバッジ

- TODO 一覧で `is_jobber_recruiting = TRUE` の TODO には **「💼 Jobber 募集中」** バッジを表示
- バッジクリックで対応する `/jobs/{posting_id}` にジャンプ

### 2.14 既存機能との差分まとめ（第三版）

- `visibility` → `visibility_scope` にカラム名変更（ENUM 値も `TEAM_MEMBERS_ONLY` → `TEAM_MEMBERS`、`TEAM_MEMBERS_AND_SUPPORTERS` → `TEAM_MEMBERS_SUPPORTERS` に整理、`JOBBER_INTERNAL` / `JOBBER_PUBLIC_BOARD` 追加）
- `memberships` テーブルで JOBBER を `role_kind='MEMBER'` として管理（F00.5 Phase 4 以降。旧 `team_members.role` enum への `JOBBER` 追加は不要）
- `job_postings.use_qr_check_in`, `time_confirmation_method`, `source_todo_id`, `auto_join_jobber_on_apply` 追加
- `jobber_profiles` 新設
- `job_time_confirmations` 新設
- `job_payments.escrow_status`, `captured_at`, `dispute_window_ends_at`, `early_release_*` 追加
- `todos.job_posting_id`, `todos.is_jobber_recruiting` 追加
- 通知タイプに `JOB_PUBLIC_BOARD_MATCH`, `JOB_TIME_CONFIRMATION_REQUESTED`, `JOB_EARLY_RELEASE_COMPLETED`, `JOB_ESCROW_CAPTURED`, `JOB_JOBBER_INVITATION`, `JOB_JOBBER_ACCEPTED` 追加

---

## 3. 手数料設計

### 3.1 手数料率（マスター決定事項）

- **Requester（募集主）**: 業務報酬の **10%** ＋ **100円** の固定額 をプラットフォーム手数料として徴収
- **Worker（受注者）**: 業務報酬の **2%** ＋ **100円** の固定額 をワーカー手数料として徴収
- **通貨**: JPY 固定
- **端数処理**: 割合計算の結果は `ROUND_HALF_UP`（四捨五入）で整数円に丸め、先に固定額 100円 を足してから合算。常に円単位の整数で扱う（円以下の端数を発生させない）

### 3.2 計算式（`JobFeeCalculator` 集約）

```
base_reward                    := 業務報酬（Workerの基本受取、Requesterが指定する入力値）
requester_fee_percent          := base_reward × 10% （ROUND_HALF_UP → 整数円）
requester_fee                  := requester_fee_percent + 100
requester_fee_tax              := (requester_fee) × 10% （消費税）
requester_total_payment        := base_reward + requester_fee + requester_fee_tax

worker_fee_percent             := base_reward × 2% （ROUND_HALF_UP → 整数円）
worker_fee                     := worker_fee_percent + 100
worker_receipt                 := base_reward - worker_fee

application_fee_amount         := requester_fee + requester_fee_tax + worker_fee  # Stripe PaymentIntent に渡す値（税込粗利）
stripe_processing_fee          := (requester_total_payment) × 3.6% （ROUND_HALF_UP → 整数円）
platform_gross_margin_excl_tax := requester_fee + worker_fee      # マスター提示表の「粗利」はこちら（税別合算）
platform_consumption_tax_hold  := requester_fee_tax               # 預かり消費税（納税義務）
platform_net_margin            := platform_gross_margin_excl_tax - stripe_processing_fee
                                 # ※ Mannschaft が課税事業者の場合、預かり消費税は別途国税に納付。
                                 #    仕入税額控除により Stripe 側支払手数料分の消費税は差し引ける。
                                 #    免税事業者期間中は `fee.tax-enabled=false` で消費税徴収せず、預かりも発生しない。
```

### 3.3 手数料計算例（3パターン必須）

| 業務報酬（Worker基本受取） | Requester支払総額（税抜） | 税込総額 | 内訳（Requester側） | Worker受取額 | Mannschaft粗利（税別・Stripe差引前） |
|---|---|---|---|---|---|
| **3,000円** | **3,400円** | **3,440円**（+消費税40円） | 3000 + (10%×3000=300) + 100 = **400円の手数料** | 3000 - (2%×3000=60) - 100 = **2,840円** | (300+100) + (60+100) = **560円** |
| **5,000円** | **5,600円** | **5,660円**（+消費税60円） | 5000 + 500 + 100 = **600円の手数料** | 5000 - 100 - 100 = **4,800円** | 600 + 200 = **800円** |
| **10,000円** | **11,100円** | **11,210円**（+消費税110円） | 10000 + 1000 + 100 = **1,100円の手数料** | 10000 - 200 - 100 = **9,700円** | 1100 + 300 = **1,400円** |

> **注**:
> - 「Requester支払総額（税抜）」= 業務報酬 + Requester 手数料（%+固定）
> - 「税込総額」= 税抜総額 + 消費税（Requester 手数料に対する 10%）。これが Requester が実際にカード決済で支払う額
> - 「Mannschaft 粗利」はマスター決定に従い税別（消費税を除外した純粋手数料合算）。Stripe 決済手数料 3.6% は税込総額に対して差し引かれ、Mannschaft の純利益（`platform_net_margin`）となる
> - 粗利 560/800/1400 円から Stripe 決済手数料 3.6% を差し引いた Mannschaft 純利益（`platform_net_margin`）は:
>   - base=3,000 → 3,440×3.6% = 124円 → 純利益 560 - 124 = **436円**
>   - base=5,000 → 5,660×3.6% = 204円 → 純利益 800 - 204 = **596円**
>   - base=10,000 → 11,210×3.6% = 404円 → 純利益 1,400 - 404 = **996円**
> - 預かり消費税（40/60/110円）は別途国税に納付義務あり。免税事業者期間は `fee.tax-enabled=false` で徴収せず、この行は 0 になる

### 3.4 消費税の取り扱い

- Mannschaft（プラットフォーム運営法人）が課税事業者となる時点以降、**Requester から徴収する手数料部分（10%+100円）に消費税 10% を加算して請求** する。
- Worker への業務報酬は Requester と Worker の間の業務委託契約の対価であり、Mannschaft は媒介にすぎない。Mannschaft が Worker に支払う扱いではなく、消費税の納付義務は Requester（発注者）と Worker（受注者）のそれぞれの課税区分に従う。
- **インボイス対応（2026年時点）**: Mannschaft 発行の**手数料請求書**はインボイス制度の適格請求書として発行可能にする（登録番号を UI とメール PDF に明記）。Worker への業務報酬部分は当事者間の取引のため、Worker 本人のインボイス登録状況に応じて Requester が適格かどうかを判定する。
- **年間課税売上高 1,000 万円未満の開業初期**: 免税事業者として運営する場合は手数料に消費税を加算しない設定フラグ（`application.properties: mannschaft.fee.tax-enabled=false`）を用意する。

### 3.5 Stripe 決済手数料（3.6%）の帰属

- Stripe 標準レート：JCB/AMEX/国際ブランドは 3.6%、Visa/MasterCard は 3.6%（2026年時点、Stripe Japan 公式レート）
- 決済手数料は **Mannschaft の粗利から引かれる**（Requester・Worker に転嫁しない）
- `application_fee_amount` の計算式（**重要・根治版**）:
  ```
  application_fee_amount = requester_total_payment_incl_tax - worker_receipt
                         = (base_reward + requester_fee + requester_fee_tax) - (base_reward - worker_fee)
                         = requester_fee + requester_fee_tax + worker_fee
  ```
  - **Stripe 決済手数料は application_fee_amount には含めない**。Stripe は platform の Stripe 残高（balance）から別途自動控除するため、application_fee_amount を減じると Worker 受取額が減って契約不整合になる
  - この値を PaymentIntent 作成時に指定すると、Stripe は税込総額から application_fee_amount を差し引いた金額を Worker の Connect アカウントへ transfer する
  - 例（base=5,000）: `application_fee_amount = 600 + 60 + 200 = 860 円`、税込総額 5,660 円から 860 円を platform、4,800 円を Worker に分配
  - Stripe 決済手数料（例 204 円）は platform 側の Stripe balance から差し引かれ、Mannschaft の純利益は 860 - 204 = 656 円となる（内訳: 税別粗利 800 円 + 預かり消費税 60 円 − Stripe 手数料 204 円）
  - Stripe 実手数料の確定値は `charge.balance_transaction` 確定後に `job_payments.stripe_fee_jpy` / `platform_net_margin_jpy` に記録する
- `transfer_data.destination` に Worker の `acct_xxx` を指定し、残額を Worker アカウントへ自動送金

### 3.6 最低報酬額の下限

- **最低 base_reward = 500 円** を下限とする（アプリ側バリデーション）
- 理由: base_reward = 500 の場合、Worker 手数料は 2%×500 + 100 = 110 円、Worker 受取額 390 円と妥当
- 500 円未満だと Worker 手数料が報酬の 20% を超え景表法・独禁法・下請法の観点で不健全
- 上限は **1,000,000 円** とし、それ以上は別途 ADMIN 承認フローを通す（将来拡張）

### 3.7 手数料プレビュー（UI で常時表示必須）

- 求人作成時：Requester 側に「業務報酬 X 円 → あなたの支払総額 Y 円（うち手数料 Z 円、消費税 W 円）」を明示
- 応募時：Worker 側に「報酬 X 円 → あなたの受取額 Y 円（手数料 Z 円）」を明示
- Backend API `POST /api/v1/jobs/fee-preview` が一元計算（フロント計算は禁止）

### 3.8 エスクロー期間中の手数料精算タイミング（第三版新規）

- **オーソリ時点**: Requester のカードから `requester_total_payment_incl_tax_jpy` を**オーソリ（事前承認）**のみ。実際の引落しは capture 時点
- **業務完了承認時点**: まだ capture しない。`escrow_status = HOLDING`、`dispute_window_ends_at = approved_at + 7 日`
- **capture 時点（早期 release or 7 日経過自動 capture）**:
  - Stripe が application_fee_amount を platform balance に、残額を Worker Express に分配
  - Stripe 決済手数料 3.6% は platform balance から別途控除（`balance_transaction` 確定後）
  - 消費税預かり分 (`platform_consumption_tax_hold_jpy`) は `job_payments` に確定記録
- **仕訳・会計処理**（F08.6 予算会計連携）:
  - エスクロー期間中（`HOLDING`）は「**未収収益**」として計上せず、Stripe の authorization 保持状態として扱う
  - capture 完了時点で「売上」として計上
  - Stripe 決済手数料確定後に純利益 `platform_net_margin_jpy` を確定
- **異議申立（DISPUTED）時の手数料**: ADMIN 仲裁結果によって分岐
  - Worker 勝 / 全額 capture → 通常の手数料徴収
  - Requester 勝 / 全額返金 → 手数料も全額返金（Stripe 決済手数料 3.6% は Mannschaft 負担、Stripe refund 経由で消滅）
  - Split → 按分比率で手数料も按分
- **7 日経過 capture の消費税計上日**: 実際の capture 日を消費税納付義務の発生日とする（税務上の役務提供日 = capture 日）

---

## 4. アクセス権限・募集範囲

### 4.1 対象ロール

| ロール | 操作可能な範囲 |
|--------|---------------|
| SYSTEM_ADMIN | 全求人・契約・決済の参照。紛争仲裁。手数料率マスター管理。全エスクロー強制 capture 権限 |
| ADMIN | 所属チーム／組織内の求人 CRUD。応募者管理・採用確定・完了承認。Worker としても動作可。**JOBBER 招待権限あり**。エスクロー紛争の一次仲裁 |
| DEPUTY_ADMIN | `MANAGE_JOBS` 権限を持つ場合: ADMIN と同等（自分が作成した求人 + 委任範囲）。**JOBBER 招待権限あり** |
| MEMBER | Worker として求人閲覧・応募。自分のチーム管理者でない限り Requester としての投稿は不可（ADMIN が DEPUTY 権限付与すれば可） |
| SUPPORTER | Worker として求人閲覧・応募（求人の `visibility_scope` が `TEAM_MEMBERS_SUPPORTERS` 以上で許可された場合のみ） |
| **JOBBER（第三版新規）** | **有償前提の登録済み助っ人**。自分が所属するチームの `JOBBER_INTERNAL` 求人を閲覧・応募可。`jobber_profiles.is_public_board_opt_in = TRUE` なら **Jobber 総合掲示板経由で `JOBBER_PUBLIC_BOARD` 求人にも応募可**。チームの他の活動（タイムライン・TODO・議事録等）へのアクセスは制限される |
| GUEST | 対象外（認証必須） |
| PUBLIC | 対象外 |

### 4.2 DEPUTY_ADMIN の細粒度権限

`deputy_admin_permissions` テーブルに `MANAGE_JOBS` を追加（既存 F01.2 の権限列挙型拡張）。

- `MANAGE_JOBS`: 求人 CRUD・応募者管理・採用確定・完了承認
  - 第三版で以下を追加:
    - **`JOBBER` ロール招待 / 招待取消**（`POST /api/v1/teams/{teamId}/jobbers/invite`, `DELETE /api/v1/teams/{teamId}/jobbers/{userId}`）
    - **TODO → 求人自動変換**（`POST /api/v1/todos/{id}/convert-to-job-posting`）
    - **運営側業務時間確定**（`POST /api/v1/job-contracts/{id}/time-confirmations`）
    - **エスクロー早期 release 受付**（Requester 側としての押下権）
- ※決済の返金・手数料率変更は ADMIN（場合により SYSTEM_ADMIN）のみ
- ※ `DISPUTED` 状態の紛争解決は ADMIN 以上

### 4.3.x スコープ別閲覧・応募権限マトリクス（第三版新規、§4.3 の前に差し込む）

| `visibility_scope` | 閲覧可能ロール（チーム内） | 応募可能ロール | 備考 |
|---|---|---|---|
| `TEAM_MEMBERS` | ADMIN / DEPUTY / MEMBER | ADMIN / DEPUTY / MEMBER | 最も閉じたスコープ |
| `TEAM_MEMBERS_SUPPORTERS` | 上記 + SUPPORTER | 上記 + SUPPORTER | 既存互換 |
| `ORGANIZATION_SCOPE` | 組織配下全チーム | 組織配下全チームの MEMBER / SUPPORTER | 既存互換 |
| `JOBBER_INTERNAL` | ADMIN / DEPUTY + **当該チームの JOBBER** | **当該チームの JOBBER のみ** | 第三版新規。チームに `JOBBER` 登録されていないユーザーは閲覧不可 |
| `JOBBER_PUBLIC_BOARD` | ADMIN / DEPUTY + **全 Mannschaft ユーザーの Jobber 登録者（`is_public_board_opt_in=TRUE`）** | **Jobber 登録済みユーザー全員（`is_public_board_opt_in=TRUE`）** | 第三版新規。`/jobs/public-board` に掲載される |
| `CUSTOM_TEMPLATE` | テンプレート依存 | テンプレート依存 | F01.7 連携 |

- 閲覧権限の実装は `JobVisibilityPolicy.canViewPosting(user, posting)` に集約
- 応募権限は `JobApplicationPolicy.canApply(user, posting)` で別途判定（閲覧 != 応募、例: ADMIN 閲覧可だが自チームの自分発注求人には応募不可など）

### 4.3 対象レベル

- [x] 組織 (Organization) — 組織横断求人（組織 ADMIN が発注、配下チーム全メンバー対象）
- [x] チーム (Team) — チーム内求人（チーム ADMIN が発注、チームメンバー対象）
- [ ] 個人 (Personal) — **Phase 13.2 で拡張検討**（当面は法的整理の観点から除外）

### 4.4 未成年者の取り扱い

- **Requester**: 18 歳以上のロール（ADMIN / DEPUTY_ADMIN）のみ投稿可能。18 歳未満が ADMIN の場合は UI 側で投稿ボタンを無効化
- **Worker**: 18 歳未満は原則参加不可。ただし下記条件を満たせば 15 歳以上から参加可能:
  - 親権者同意書の電子提出（F04.9 確認通知システムで取得）
  - 危険作業カテゴリ（`DANGEROUS` フラグあり）は不可
  - 労基法の労働時間制限に抵触しないよう UI で時間帯制限を自動適用（15-17歳は 20:00～5:00 不可）
- 15 歳未満は参加不可（児童労働規制）

---


## ドキュメント構成

| ファイル | 内容 |
|---|---|
| [01_data_model.md](01_data_model.md) | §5 データモデル |
| [02_api_design.md](02_api_design.md) | §6 API設計 |
| [03_ui_payment.md](03_ui_payment.md) | §7 UI設計 / §8 Stripe Connect統合 |
| [04_quality_compliance.md](04_quality_compliance.md) | §9 非機能要件 / §10 セキュリティ / §11 ユーザビリティ / §12 法務 / §13 エラー処理 / §14 保守性 |
| [05_implementation_refs.md](05_implementation_refs.md) | §15 実装タスク / §16 テスト / §17 関連ファイル / §18 ステータス / §19 未解決 / §20 変更履歴 |
