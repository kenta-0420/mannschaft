## 7. UI設計

### 7.1 Requester 側

- **求人投稿フォーム**（`/jobs/new`）: ステップウィザード（基本情報 → 日時場所 → 報酬 → 募集条件 → 公開範囲 → プレビュー）
- **手数料プレビューパネル**（フォーム側面固定）: base_reward 入力で即時 API 呼び出し、支払総額・手数料・税を表示
- **求人管理ダッシュボード**（`/jobs/manage`）: 自分の求人一覧、応募件数、採用状況、完了待ち、支払い確認
- **応募者一覧**（`/jobs/{id}/applications`）:
  - プロフィール・自己PR・採用／不採用ボタン
  - **星評価表示は行わない**（公開評価システム廃止）
  - サイドに「**このWorkerの過去依頼履歴**」パネルを表示（`GET /api/v1/teams/{teamId}/workers/{workerId}/history`、過去履歴がある場合のみ）
- **契約詳細 / 完了承認画面**（`/contracts/{id}`）: 完了報告確認・承認／差し戻しボタン・チャットへのリンク、チェックイン／アウト実測表示
- **QR コード表示ページ**（`/contracts/{id}/qr?type=IN` / `?type=OUT`）:
  - 大きな QR コード（画面中央、80vw 正方形）＋ 手動入力用 6 桁 `short_code` 併記
  - 自動リフレッシュ（SSE or 55 秒 polling で新トークン取得）
  - 残り秒数プログレスバー（`expires_at` カウントダウン）
  - 画面スクリーンショット不推奨の注意書き（「古い画面の撮影不可」）
  - Worker がスキャンした瞬間に Requester 画面に成立 toast 表示（WebSocket or SSE push）
- **履歴ダッシュボード**（`/teams/{teamId}/jobs/history`）:
  - 表形式（sticky ヘッダ）、期間・Worker・ステータス・金額レンジのフィルタサイドバー
  - 各行クリックで契約詳細モーダル
  - 右上「CSV エクスポート」ボタン
- **内部評価メモ記入画面**: 承認画面のモーダルで 1〜5 の内部参考数値（任意）＋ コメント 1000 字まで。「これは内部記録です・Worker 本人または同一チーム ADMIN のみ閲覧可能」と明示
- **返金申請画面**: キャンセル・紛争時の返金理由入力

### 7.2 Worker 側

- **求人検索**（`/jobs`）: カテゴリ・日時・報酬範囲・場所（半径 km）でフィルター
- **求人詳細**（`/jobs/{id}`）: 業務内容・Requester情報・手数料込み受取額・応募ボタン
- **応募ダイアログ**: 自己PR入力・Connect 未準備なら「登録に進む」案内
- **契約一覧**（`/contracts`）: 自分の契約（応募中・進行中・完了）
- **チェックイン／アウト画面**（`/contracts/{id}/scan`）:
  - 画面中央に「カメラで QR を読み取る」ボタン（BarcodeDetector API or `@zxing/browser` フォールバック）
  - カメラ権限リクエスト UI（初回のみ、拒否時の説明ダイアログ）
  - 手動入力タブ: 6 桁コード入力欄（フォールバック）
  - 位置情報権限の説明文（「業務場所の確認のために位置情報を取得します。チーム ADMIN のみ閲覧可、90 日後自動削除」）
  - スキャン成功 → 即座に「✅ チェックイン完了」表示、契約詳細へ戻る
  - オフライン時は「📡 オフラインです。オンライン復帰時に自動送信します」トーストを表示し IndexedDB に保存
- **完了報告画面**: 業務写真アップロード（任意、R2 Storage）・コメント。前提条件: `CHECKED_OUT` 済み
- **Connect オンボーディング UI**: ステッパー「口座登録進捗」、詳細は Stripe Hosted Onboarding に遷移
- **マイページ履歴**（`/me/jobs/history`）: 自分の過去契約一覧（プライベート、他 Worker には見えない）
- **評価確認画面**（自分宛の内部メモのみ）: Worker 本人宛に書かれたコメントを閲覧可能。書き込みは Requester 側のみ

### 7.3 モバイル / PWA 対応

- F11.1 の PWA 基盤を活用し、求人一覧・契約一覧・完了報告画面をオフラインで閲覧可能（IndexedDB キャッシュ）
- オフラインでの応募・完了報告・**チェックイン／アウト**は IndexedDB `offlineQueue` に下書き保存 → オンライン時自動同期
- QR スキャンは Camera API（HTTPS 必須）を使用。F11.1 の既存 Service Worker がカメラ権限・位置情報権限を事前プロンプト
- プッシュ通知（F04.3）で採用確定・チェックイン成立・完了承認を即時通知
- カメラ起動遅延対策: ボタン押下時に `getUserMedia` を先行実行し、ストリームをキャッシュ（§11.1 で詳述）

### 7.4 共通コンポーネント

- `<JobFeePreview :base-reward="n">`: 手数料プレビュー（SSR禁止、API経由）
- `<JobStatusBadge :status="s">`: 状態表示（i18n 対応、CHECKED_IN/CHECKED_OUT/**TIME_CONFIRMED**/**AUTHORIZED**/**CAPTURED**/**PAID** 対応）
- `<ConnectStatusIndicator :status="s">`: Connect 口座状態
- `<QrCheckInDisplay :contract-id="n" :type="IN|OUT">`: Requester 画面用 QR 表示コンポーネント（自動ローテーション・short_code 併記）
- `<QrScanner @scanned="onScan">`: Worker 画面用 QR スキャナー（カメラ権限 + 手動入力フォールバック）
- `<WorkerHistoryPanel :team-id="n" :worker-id="m">`: 再応募時の過去履歴パネル
- `<JobHistoryTable :team-id="n">`: 履歴ダッシュボード表
- `<InternalReviewMemoForm :contract-id="n">`: 内部評価メモ記入（星は表示しない、数値入力のみ）
- `<JobberInviteButton :team-id="n">`（第三版）: Jobber 招待モーダル起動ボタン
- `<JobberInvitationAcceptCard :token="t">`（第三版）: 招待受諾 UI
- `<JobberProfileForm>`（第三版）: Jobber プロフィール編集
- `<PublicBoardFilterSidebar>`（第三版）: 総合掲示板の絞り込みサイドバー
- `<PublicBoardJobCard>`（第三版）: 総合掲示板の求人カード（チーム名のみ、距離表示）
- `<EscrowStatusBadge :payment-id="n">`（第三版）: 「📦 預かり中 あと X 日 Y 時間」バッジ + カウントダウン
- `<EscrowEarlyReleaseButton :payment-id="n" :role="requester|worker">`（第三版）: 早期 release 押下ボタン
- `<TimeConfirmationForm :contract-id="n">`（第三版）: 運営側の業務時間入力フォーム（単体 + 一括 CSV）
- `<TimeConfirmationApprovalCard :confirmation-id="n">`（第三版）: Worker 承認 UI
- `<TodoJobberFlagToggle :todo-id="n">`（第三版）: TODO 編集画面の「Jobber 募集に切り替える」ボタン
- `<TodoToJobPostingModal :todo-id="n">`（第三版）: TODO → 求人変換モーダル（補完フォーム）
- `<TodoJobberBadge>`（第三版）: TODO 一覧で「💼 Jobber 募集中」バッジ
- ※ 旧 `<RatingStars>` は **廃止**（公開評価システム削除）

### 7.5 Requester 側（第三版追加 UI）

- **Jobber 管理画面** (`/teams/{teamId}/jobbers`):
  - 所属 JOBBER 一覧（氏名・プロフィール・総契約数）
  - 「+ JOBBER を招待する」ボタン
  - 招待中（PENDING）の招待一覧と取消
- **Jobber 招待モーダル**:
  - 既存ユーザー検索 or メール入力タブ
  - 推定時給帯・想定カテゴリ入力（任意）
  - メッセージ入力（500 字）
  - 送信で `jobber_team_invitations` INSERT + F04.9 確認通知送信
- **求人作成フォーム**（既存 `/jobs/new` 拡張）:
  - `visibility_scope` 選択で `JOBBER_INTERNAL` / `JOBBER_PUBLIC_BOARD` を追加
  - 募集人数入力時に `capacity >= 10` で自動的に `time_confirmation_method = 'ORG_CONFIRM'` へ切替 + QR 使用チェックを disable + 通知
  - `JOBBER_PUBLIC_BOARD` 選択時、「応募者を自動的にチームに JOBBER として加入させる」チェック
- **運営側業務時間確定画面**（`/contracts/{id}/confirm-time`、大規模募集用）:
  - Worker 一覧 + 各行に 開始時刻・終了時刻・休憩分・備考
  - CSV アップロードで一括反映
  - 「全 Worker に確定通知を送信」ボタン
- **エスクロー期間中のダッシュボード** (`/jobs/manage` に追加セクション):
  - 「📦 預かり中の契約 X 件」一覧
  - 各行に「早期 release を提案」ボタン（Worker 側ボタンがすでに ON の場合は即 capture）
  - 残り日数・異議提起の有無を表示
- **TODO 編集画面**（既存 `/todos/{id}/edit` 拡張）:
  - 「Jobber 募集に切り替える」ボタン（ADMIN / DEPUTY(`MANAGE_JOBS`) のみ表示）
  - 押下で `<TodoToJobPostingModal>` 起動

### 7.6 Worker 側（第三版追加 UI）

- **Jobber プロフィール設定** (`/me/jobber-profile`):
  - 自己紹介・スキル・希望時給・希望エリア・availability
  - **「Jobber 総合掲示板に公開する」トグル** (`is_public_board_opt_in`)
  - 新着通知の条件フィルタ編集
- **Jobber 総合掲示板** (`/jobs/public-board`):
  - フィルタサイドバー（`<PublicBoardFilterSidebar>`）
  - カード一覧（`<PublicBoardJobCard>`）、各カードに距離表示
  - 応募ボタンで既存の応募フロー（`auto_join_jobber_on_apply = TRUE` の求人は、応募時に「このチームに JOBBER として加入します」確認ダイアログ）
- **招待受諾画面** (`/jobber-invitations/accept?t={token}`):
  - 招待元チーム名・ADMIN 名・メッセージ表示
  - 「受諾する」「辞退する」ボタン
  - 受諾後、Jobber プロフィール初期設定フローへ誘導（まだ未作成の場合）
- **業務時間承認画面** (`/contracts/{id}/time-approval`):
  - 運営提示の時間・金額を大きく表示
  - 「✅ 承認する」ボタン + 「⚠️ 異議を申し立てる」ボタン
  - 72 時間タイマー表示（残り時間）
- **エスクロー期間中のバッジ** (契約詳細):
  - `<EscrowStatusBadge>` で預かり中表示
  - 「✅ 早期に報酬を確定する」ボタン（Worker 押下で `early_release_worker_approved_at` が立つ）
  - Requester も押下していれば即 capture 実行の通知

---

## 8. Stripe Connect 統合

### 8.1 アーキテクチャ

- **プラットフォームアカウント**: Mannschaft の Stripe アカウント（API キーは本番/テストで分離）
- **Connected Express アカウント**: Worker ごとに作成。KYC・銀行口座管理は Stripe が完全に保有
- **課金方式**: **Destination Charges**（プラットフォーム側 PaymentIntent + `transfer_data.destination` で Worker に送金）
- **Stripe API バージョン**: `2025-06-30.clover`（契約時点の最新安定版を固定、環境変数 `STRIPE_API_VERSION` で明示）。`Stripe-Version` ヘッダーを全リクエストに付与。

### 8.2 Express アカウント作成フロー

```
1. Worker が初回採用確定直前にオンボーディング画面へ
2. POST /api/v1/stripe/connect/onboarding-link
   ├─ Stripe.accounts.create(type='express', country='JP', capabilities={card_payments, transfers})
   ├─ DB に stripe_connect_accounts(status=ONBOARDING) を INSERT
   └─ Stripe.accountLinks.create(account=acct_xxx, type='account_onboarding',
                                  return_url=.../connect/return, refresh_url=.../connect/refresh)
3. Worker が Stripe hosted onboarding で本人確認・口座登録
4. 完了後 return_url に戻る
5. Webhook account.updated 受信
   ├─ charges_enabled=true && payouts_enabled=true → status=READY
   └─ それ以外 → status=RESTRICTED（requirements を DB に保存）
```

### 8.3 Destination Charges の PaymentIntent 構造

```python
PaymentIntent.create(
  amount=requester_total_payment_incl_tax_jpy,   # 税込総額（例 5,660）
  currency='jpy',
  capture_method='manual',                       # 事前オーソリ
  payment_method_types=['card'],
  application_fee_amount=(requester_fee_jpy + requester_fee_tax_jpy + worker_fee_jpy),  # 例 860
  transfer_data={'destination': worker_acct_id},
  on_behalf_of=worker_acct_id,                   # 税務上 Worker の売上として扱う
  metadata={
    'job_contract_id': 123,
    'job_posting_id': 456,
    'requester_user_id': 789,
    'worker_user_id': 101
  },
  customer=requester_stripe_customer_id,
  idempotency_key=f'contract-{contract_id}-intent'
)
```

- `application_fee_amount` は **Stripe 決済手数料を減じない純 platform 取り分**（= requester_fee + requester_fee_tax + worker_fee）を指定する。Stripe は platform 側 Stripe balance から決済手数料を別途差し引くため、application_fee_amount を減らすと Worker 送金額が不足して契約不整合になる
- `on_behalf_of` を指定することで、**Stripe ダッシュボード上もこの決済は Worker の売上として扱われる**。税務上の整合性に重要

### 8.4 承認時の capture

```python
PaymentIntent.capture(
  payment_intent_id,
  idempotency_key=f'contract-{contract_id}-capture'
)
```

capture で自動的に transfer が実行され、`application_fee_amount` 差し引き後の金額が Worker の Express アカウントへ送金される。

### 8.5 Webhook ハンドリング

プラットフォーム用と Connect 用で **Webhook エンドポイントを分離**（Stripe 推奨）。両方とも `Stripe-Signature` 検証必須。

| イベント | エンドポイント | 処理 |
|---------|------------|------|
| `payment_intent.succeeded` | platform | `job_payments.status=SUCCEEDED`、`captured_at`記録 |
| `payment_intent.payment_failed` | platform | `FAILED`、Requester へ通知 |
| `payment_intent.canceled` | platform | `CANCELLED` |
| `charge.refunded` | platform | `REFUNDED` or `PARTIALLY_REFUNDED` |
| `charge.updated` | platform | `balance_transaction` 確定時に `stripe_fee_jpy` / `platform_net_margin_jpy` 更新 |
| `transfer.created` | platform | `stripe_transfer_id` 記録 |
| `account.updated` | connect | `stripe_connect_accounts.status` 更新 |
| `account.application.deauthorized` | connect | `status=DISABLED`、将来発注不可 |
| `payout.failed` | connect | Worker へ通知、口座再登録促す |
| `capability.updated` | connect | capabilities 状態のミラー |

**冪等性**:
- `stripe_events` テーブル（evt_xxx, event_type, processed_at, idempotency_key を持つ共通テーブル）を用意し、同一 event_id の再処理を block
- 各 domain テーブル（`job_payments.webhook_event_ids` JSON）にも記録し多重更新を防ぐ

### 8.6 Payout スケジュール

- 日本のデフォルト: 週次（月曜締め、金曜払い出し）
- Stripe Instant Payouts は将来オプション（手数料 1.5%、Worker 希望時のみ有効化）

### 8.7 API バージョン固定ポリシー

- `STRIPE_API_VERSION` を環境変数で明示（例: `2025-06-30.clover`）
- Stripe SDK 初期化時に `Stripe.setApiVersion(STRIPE_API_VERSION)` で固定
- Stripe の API アップグレード時は：
  1. ステージング環境で新バージョンに切替・E2E 全通過
  2. 本番は段階リリース（feature flag `stripe.api-version-new` で新旧切替）
  3. Webhook エンドポイント側は複数バージョン並行対応（Stripe 設定で「複数エンドポイント」方式）

### 8.8 リアルタイム性と整合性

- Webhook が遅延する場合でも、`PaymentIntent.retrieve` で最新状態を DB に同期する「リコンシリエーションバッチ」を 15 分間隔で実行
- 毎日深夜に前日分の balance_transaction を Stripe Reports API から取得し、`job_payments.stripe_fee_jpy` と `platform_net_margin_jpy` を確定する

### 8.9 エスクロー 7 日間タイマー（第三版新規）

#### 8.9.1 タイマーの動作原理

- 完了承認時点で `dispute_window_ends_at = approved_at + 7 日` を設定し、`escrow_status = HOLDING` に遷移
- Stripe の `capture_method=manual` による authorization hold は card-issuer 側で最大 7 日（カード種別・発行者によって 2〜7 日と幅あり）
- **安全マージン**: Mannschaft 側は **完了承認から 6 日 22 時間経過時点** で capture バッチを走らせることで、Stripe authorization 失効を回避する
- 早期 release（両者合意）で 6 日 22 時間を待たずに即 capture

#### 8.9.2 authorization 切れ対策

- **Stripe 側 authorization の段階的失効**: カード種別ごとの失効時刻を `payment_intent.latest_charge.transaction_details` から取得し、`job_payments.stripe_hold_expires_at` に記録
- capture バッチは `LEAST(dispute_window_ends_at, stripe_hold_expires_at - 2 時間)` をターゲットに発火
- **失効してしまった場合のフォールバック**:
  - `payment_intent.canceled`（authorization 失効による）Webhook 受信
  - 再オーソリ（`PaymentIntent.create` を同じ metadata で再実行）を Requester に依頼する通知 `JOB_REAUTHORIZATION_REQUIRED`
  - 再オーソリできない場合は紛争扱い → ADMIN 仲裁

#### 8.9.3 異議申立中の authorization 延長戦略

- 異議申立 (`DISPUTED`) で 7 日以内に仲裁が決着しないケースがある。Stripe authorization は 7 日超過で失効するため、以下の **「先 capture 後返金」方式** を採用:
  1. 6 日 22 時間の時点で `escrow_status = 'DISPUTED'` でも一旦 capture（`escrow_status → DISPUTED_CAPTURED` 新設、もしくは `captured_under_dispute=TRUE` フラグ）
  2. ADMIN 仲裁結果:
     - Worker 勝 → そのまま transfer 確定（追加操作なし）
     - Requester 勝 → `refunds.create()` で全額返金
     - Split → 部分 refund
- 実装上の選択: **`job_payments.escrow_status` に `DISPUTED_CAPTURED` を追加**（`HOLDING → DISPUTED → DISPUTED_CAPTURED → RELEASED/REFUNDED`）

#### 8.9.4 早期 release 時の即時 capture

- 両者承認成立時点で即座に PaymentIntent capture 実行
- `escrow_status: HOLDING → RELEASED`、`captured_at = NOW()`
- 以降は通常の `charge.updated` Webhook で `balance_transaction` 確定 → `stripe_fee_jpy` / `platform_net_margin_jpy` 更新

#### 8.9.5 バッチジョブ: `EscrowAutoCaptureJob`

```
@Scheduled(fixedDelay = 10 minutes)
execute():
  candidates = job_payments
    WHERE escrow_status IN ('HOLDING', 'DISPUTED')
      AND dispute_window_ends_at <= NOW()
      AND NOT (escrow_status = 'DISPUTED' AND dispute_created_at > NOW() - interval '2 hour')
      FOR UPDATE SKIP LOCKED
  for p in candidates:
    try:
      paymentIntent.capture(p.stripe_payment_intent_id, idempotency_key=f"capture-{p.id}")
      if p.escrow_status == 'HOLDING':
        p.escrow_status = 'RELEASED'
      else:
        p.escrow_status = 'DISPUTED_CAPTURED'  # 異議中は DISPUTED_CAPTURED へ
      p.captured_at = NOW()
    except StripeError as e:
      if e.code == 'payment_intent_authentication_failed':
        notifyRequester(REAUTHORIZATION_REQUIRED)
      else:
        retryWithBackoff(p, attempts=3)
```

- **同時実行防止**: `FOR UPDATE SKIP LOCKED` で複数ノード並行実行時の二重 capture を防ぐ
- **Idempotency**: Stripe に `idempotency_key = "capture-{payment_id}"` を渡して二重 capture リクエストを Stripe 側でも拒否

#### 8.9.6 Webhook ハンドリング追加分

| イベント | エンドポイント | 処理 |
|---------|------------|------|
| `payment_intent.amount_capturable_updated` | platform | エスクロー中のオーソリ額変化を検知、監査ログのみ記録 |
| `payment_intent.canceled`（authorization 失効） | platform | `escrow_status = 'CANCELLED'`、Requester へ `JOB_REAUTHORIZATION_REQUIRED` 通知 |

---

