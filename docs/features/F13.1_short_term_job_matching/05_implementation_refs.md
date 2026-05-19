## 15. 実装タスク分解

### 15.1 部隊構成と成果物

| 部隊 | 主タスク | 主要ファイル見込み |
|------|----------|-----------------|
| **Entity / Repository 部隊** | 11 テーブル Entity / JPARepository（job_check_ins・job_qr_tokens 追加） | 22 ファイル（Entity 11 + Repository 11） |
| **Service / Config 部隊** | JobFeeCalculator、JobPostingService、JobContractService、JobPaymentService、StripeConnectService、StripeConfig、**JobQrTokenService**（署名・検証・発行）、**JobCheckInService**（チェックイン成立・状態遷移）、**JobHistoryService**（履歴集計・CSV）、**GeolocationService**（暗号化・距離判定） | 16 ファイル |
| **Controller / DTO 部隊** | 上記 API 一覧のコントローラ、Request / Response DTO、**JobQrTokenController**、**JobCheckInController**、**JobHistoryController** | 11 Controller + 32 DTO = 43 ファイル |
| **Webhook 部隊** | PlatformStripeWebhookController、ConnectStripeWebhookController、StripeEventHandler（per-event） | 12 ファイル |
| **Frontend Requester 部隊** | 投稿フォーム・管理ダッシュボード・応募者管理・完了承認・**QR 表示ページ**・**履歴ダッシュボード**・**内部評価メモ入力** | 14 Vue ファイル + composable 6 |
| **Frontend Worker 部隊** | 検索・詳細・応募・契約・**QR スキャナー**・完了報告・Connect オンボーディング・**マイページ履歴** | 13 Vue ファイル + composable 7 |
| **Frontend 共通部隊** | `<JobFeePreview>`、`<JobStatusBadge>`（CHECKED_IN/OUT 追加）、`<ConnectStatusIndicator>`、**`<QrCheckInDisplay>`**、**`<QrScanner>`**、**`<WorkerHistoryPanel>`**、**`<JobHistoryTable>`**、**`<InternalReviewMemoForm>`**、type 定義 | 共通コンポーネント 10、types 6 |
| **PWA / オフライン部隊** | F11.1 既存 `useOfflineQueue` に `jobCheckIn` タイプ追加、Service Worker 拡張、Background Sync | 4 ファイル |
| **Batch / 運用部隊** | SYSTEM_ADMIN ダッシュボード、リコンシリエーションバッチ、**GeolocationPurgeJob**（90日削除）、**JobQrTokenCleanupJob**（失効掃除）、**CheckoutMissingAlertJob**（未チェックアウト警告） | 10 ファイル |
| **Test 部隊** | Unit / Integration / E2E、Stripe CLI による Webhook テスト、**QR トークン署名検証**、**オフラインチェックイン**、**履歴集計**、**権限境界（他チームの履歴見えないこと）** | 55+ テストファイル |

### 15.2 依存関係・進軍順序

```
Phase 13.1.1: DB・基盤（Flyway 13 ファイル + Entity / Repository、v_worker_team_history ビュー含む）
    ↓
Phase 13.1.2: Stripe Connect 統合（StripeConfig + Connect Service + Webhook）
    ↓
Phase 13.1.3: 求人 CRUD（JobPostingService + Controller + Frontend Requester）
    ↓
Phase 13.1.4: 応募・採用（JobApplicationService + 排他制御）
    ↓
Phase 13.1.5: QR チェックイン／アウト（JobQrTokenService + JobCheckInService + QrCheckInDisplay + QrScanner + オフライン対応）
    ↓
Phase 13.1.6: 決済（JobPaymentService + PaymentIntent フロー）
    ↓
Phase 13.1.7: 完了承認・Capture・Transfer
    ↓
Phase 13.1.8: 内部評価メモ（公開なし版）・紛争
    ↓
Phase 13.1.9: 履歴ダッシュボード・再応募時過去履歴パネル・Worker マイページ履歴・CSV エクスポート
    ↓
Phase 13.1.10: 未成年同意・法定帳票出力
    ↓
Phase 13.1.11: 運用ダッシュボード・リコンシリエーション・Geolocation 削除バッチ
    ↓
Phase 13.1.12: 【第三版】JOBBER ロール基盤（memberships + JobberProfile + 招待フロー。F00.5 Phase 4 完了後に着手）
    ↓
Phase 13.1.13: 【第三版】Jobber 総合掲示板（/jobs/public-board + 絞り込み + 新着通知）
    ↓
Phase 13.1.14: 【第三版】エスクロー 7 日システム（escrow_status + EscrowAutoCaptureJob + 早期 release）
    ↓
Phase 13.1.15: 【第三版】運営確定方式（job_time_confirmations + 72 時間タイムアウト + Worker 承認 UI）
    ↓
Phase 13.1.16: 【第三版】TODO → 求人変換（todos 拡張 + 変換 API + モーダル）
    ↓
Phase 13.1.17: 【第三版】統合 E2E・総合掲示板パフォーマンステスト・エスクロー境界値テスト
```

各 Phase はそれぞれ PR 化し、main マージ → CI 合格を確認してから次へ進む。

### 15.3 第三版追加タスク一覧

| 部隊 | タスク | 主要ファイル |
|---|---|---|
| **Entity / Repository（第三版）** | `JobberProfile`, `JobberTeamInvitation`, `JobTimeConfirmation` + JPARepository | 6 ファイル |
| **Service（第三版）** | `JobberProfileService`, `JobberInvitationService`, `TeamRolePolicy`（JOBBER 対応）, `JobVisibilityPolicy`, `JobApplicationPolicy`（Jobber 版）, `TimeConfirmationService`, `EscrowService`, `JobberPublicBoardService`, `JobberPublicBoardNotifier`, `TodoToJobPostingService` | 10 ファイル |
| **Controller（第三版）** | `JobberController`, `JobberInvitationController`, `JobberProfileController`, `JobPublicBoardController`, `JobTimeConfirmationController`, `JobEscrowController`, `TodoConversionController` | 7 Controller + 約 18 DTO |
| **Batch（第三版）** | `EscrowAutoCaptureJob`, `JobTimeConfirmationAutoApprovalJob`, `JobberInvitationExpiryJob`, `JobberPublicBoardNotifier`（@Scheduled） | 4 ファイル |
| **Webhook（第三版）** | `payment_intent.amount_capturable_updated` ハンドラ、`payment_intent.canceled`（authorization 失効）ハンドラ | 2 ファイル |
| **Frontend Requester（第三版）** | Jobber 招待モーダル・管理画面・運営業務時間確定画面・エスクローダッシュボード・TODO 変換モーダル | 約 8 Vue + 5 composable |
| **Frontend Worker（第三版）** | Jobber プロフィール編集・総合掲示板・招待受諾・時間承認画面・エスクローバッジ | 約 6 Vue + 4 composable |
| **Frontend 共通（第三版）** | `<JobberInviteButton>`, `<JobberInvitationAcceptCard>`, `<JobberProfileForm>`, `<PublicBoardFilterSidebar>`, `<PublicBoardJobCard>`, `<EscrowStatusBadge>`, `<EscrowEarlyReleaseButton>`, `<TimeConfirmationForm>`, `<TimeConfirmationApprovalCard>`, `<TodoJobberFlagToggle>`, `<TodoToJobPostingModal>`, `<TodoJobberBadge>` | 12 コンポーネント |
| **i18n（第三版）** | `jobs.json` に約 60 キー追加（6 言語） | 6 ファイル更新 |
| **Test（第三版）** | JOBBER 権限境界・招待受諾フロー・総合掲示板フィルタ・エスクロー 7 日タイマー境界値・運営確定方式・TODO 変換テスト | 30+ テストファイル追加 |

---

## 16. テスト項目

### 16.1 単体テスト（Unit）

- `JobFeeCalculator`: 境界値（500円 / 1,000,000円 / 税込計算 / 四捨五入 / 消費税 OFF）
- State machine（`JobContractStateMachine`）: **新状態 CHECKED_IN / CHECKED_OUT を含む全遷移テーブル**、不正遷移を reject（例: MATCHED → CHECKED_OUT は不可）
- 権限ポリシー（`JobPolicy`, `JobHistoryPolicy`): 各ロール × 操作の全組合せ。**特に「他チーム ADMIN が別チームの履歴を見られないこと」を専用テストケースで検証**
- Webhook ハンドラ: 冪等性（同一 event_id 2 回処理 → 2 回目スキップ）
- **`JobQrTokenService`**:
  - 署名生成 → 検証の正常系
  - 改ざんトークンの検証拒否（署名不一致）
  - 期限切れトークンの拒否（`expires_at` 経過）
  - nonce 再利用の拒否（`used_at IS NOT NULL`）
  - 別契約 Worker が他契約のトークンを使えないことの検証
  - `kid` 指定での鍵ローテーション動作
- **`GeolocationService`**: Haversine 距離計算の精度（東京 – 大阪などの実データ）、500 m 閾値判定、AES-256-GCM 暗号化 / 復号
- **`JobHistoryService`**: 集計の正確性（完了のみカウント、CANCELLED は除外）、`total_work_minutes` の集計

### 16.2 統合テスト（Integration）

- PostgreSQL/MySQL Testcontainers
- `SELECT ... FOR UPDATE` + Advisory Lock の同時応募耐性（JUnit + 複数スレッド）
- Flyway 全マイグレーション適用（V13.001 〜 V13.013）
- Spring REST + @WebMvcTest
- **オフラインチェックイン**:
  - 電波なし状態で `scanned_at` 設定 → 10 分後オンライン復帰 → リプレイ送信 → 成立確認
  - 同一 nonce の 2 回送信 → 2 回目は 409 返却
  - `scanned_at` が `expires_at` を超えている場合の拒否
- **履歴集計**: 1000 件の契約を投入してフィルタ + ページング + CSV エクスポートの性能 (2 秒以内)

### 16.3 E2E（Playwright）

- Requester シナリオ: 求人投稿 → 応募者採用 → **QR 表示** → Worker チェックイン確認 → QR 表示（OUT）→ チェックアウト確認 → 完了承認 → 内部評価メモ記入 の全フロー
- Worker シナリオ: 求人応募 → Connect オンボーディング → **QR スキャン（IN）** → 業務 → **QR スキャン（OUT）** → 完了報告
- **QR チェックインシナリオ**:
  - 正常系: トークン発行 → 読取 → CHECKED_IN 遷移
  - 失効トークン拒否（TTL 経過後）
  - 再利用拒否（同一トークン 2 回目）
  - 手動コード入力フォールバック成立
  - Geolocation 拒否時も成立すること
  - Geolocation 乖離時の Requester 警告通知
- **オフラインチェックインシナリオ**: Playwright の `context.setOffline(true)` → スキャン → オンライン復帰 → 自動送信成立
- **履歴ダッシュボードシナリオ**:
  - チーム A ADMIN がチーム A の履歴を閲覧可能
  - チーム A ADMIN がチーム B の履歴 API を叩くと 403
  - CSV エクスポート → ダウンロード内容を検証
  - 再応募時の過去履歴パネル表示
- **Worker マイページ履歴**: Worker 本人のみアクセス可、他 Worker は 403
- 紛争シナリオ: 3 回差し戻し → 紛争モード → ADMIN 仲裁
- キャンセルシナリオ: MATCHED 24h 以内キャンセル
- Stripe は Stripe Mock / Stripe CLI テストモードを使用

### 16.4 Stripe CLI Webhook テスト

```bash
stripe listen --forward-to localhost:8080/api/v1/webhooks/stripe/platform
stripe trigger payment_intent.succeeded
stripe trigger charge.refunded
```

- 全 Webhook イベントで DB 状態が正しく更新されることを検証
- 不正署名を挿入して 400 応答確認

### 16.5 手数料計算の境界値

| テストケース | 期待結果 |
|-----------|--------|
| base=500 | r_fee=150 (50+100), w_fee=110, worker=390 |
| base=499 | バリデーション 400 |
| base=1,000,000 | r_fee=100100, w_fee=20100, worker=979,900 |
| base=1,000,001 | バリデーション 400 |
| base=1001（税抜割合端数確認） | r_fee_percent=100.1→100 (四捨五入)、fee=200、tax=20 |

### 16.6 第三版追加テスト項目

#### 16.6.1 JOBBER ロール権限境界（必須）

- `TeamRolePolicy.canAccessTeamFeature(JOBBER, feature)` が許可 feature ホワイトリストのみ TRUE を返すこと
- JOBBER がチームタイムライン GET を叩くと 403
- JOBBER がチーム TODO GET を叩くと 403（自分に割り当てられた Jobber 募集由来 TODO のみ 200）
- JOBBER がチーム議事録 GET を叩くと 403
- JOBBER が自分の `jobber_profiles` GET/PUT は 200
- JOBBER が他チームの契約詳細 GET を叩くと 403
- **マルチチーム所属 JOBBER テスト**: チーム A 所属 JOBBER がチーム A の `JOBBER_INTERNAL` 求人を見られる／チーム B の同スコープ求人は見られない

#### 16.6.2 JOBBER 招待フロー

- ADMIN が存在ユーザーを招待 → `PENDING` 状態になる
- 招待された側が期限内に受諾 → `team_members` に JOBBER INSERT、`jobber_profiles` 自動作成
- 期限（72 時間）超過で `status = EXPIRED`、受諾試行すると 410 Gone
- 既に JOBBER 登録済みの招待対象に二重招待すると 409
- ADMIN が PENDING 招待を取消 → `status = REVOKED`
- 招待取消後に受諾試行すると 410
- **メール招待**: 未登録ユーザーへのメール招待 → アカウント作成 → 同一トークンで受諾成立

#### 16.6.3 Jobber 総合掲示板

- `is_public_board_opt_in = FALSE` のユーザーが `/jobs/public-board` を叩くと 403
- 絞り込み条件の AND 結合: `category=PHOTO` + `reward_min=3000` + `distance_km=25` で正確に絞れる
- ページング: 20 件区切り + cursor が正しく機能する
- 新着通知: `JobberPublicBoardNotifier` が条件マッチユーザーに通知送信（DB モック + 通知キュー検証）
- 応募時の自動 JOBBER 加入（`auto_join_jobber_on_apply=TRUE`）の成立 / 拒否

#### 16.6.4 エスクロー 7 日タイマー

- **境界値テスト**:
  - `dispute_window_ends_at - 1 秒` で `EscrowAutoCaptureJob` 実行 → capture されない
  - `dispute_window_ends_at + 1 秒` で実行 → capture 実行、`escrow_status = RELEASED`
  - 6 日 22 時間時点での強制 capture（Stripe authorization 失効回避）
- **早期 release**:
  - Requester のみ押下 → `escrow_status` は `HOLDING` のまま、waiting_for=`WORKER`
  - Worker のみ押下 → 同上、waiting_for=`REQUESTER`
  - 両者押下 → 即 capture、`escrow_status = RELEASED`
  - 3 回目以降の押下 → 409（二重押下防止）
- **異議申立**:
  - `HOLDING` 中の dispute → `escrow_status = DISPUTED`
  - 期限切れ後の dispute 試行 → 409
  - `DISPUTED_CAPTURED` 状態での仲裁判断 → refund / partial refund が正しく実行される
- **Stripe authorization 失効**:
  - モックで `payment_intent.canceled` を受信 → `escrow_status = CANCELLED`、Requester に再オーソリ通知

#### 16.6.5 運営確定方式（ORG_CONFIRM）

- **募集人数制約**:
  - `capacity >= 10` で `time_confirmation_method = QR_CHECKIN` 指定 → 400 バリデーション
  - `capacity < 10` で `ORG_CONFIRM` 指定 → 200 OK（選択可）
- **時間確定フロー**:
  - Requester が複数 Worker 分を一括入力 → 各 Worker に通知送信
  - Worker 承認で `status = APPROVED_BY_WORKER`, 契約 `TIME_CONFIRMED`
  - Worker 異議提起で `DISPUTED`、`job_dispute_cases` が `dispute_source = TIME_CONFIRMATION` で INSERT
  - 72 時間無反応 → `AUTO_APPROVED`、契約 `TIME_CONFIRMED`
  - 運営が差し戻し（新 version 作成） → 旧 version `REVOKED`

#### 16.6.6 TODO → Jobber 募集変換

- TODO 作成者 = ADMIN なら変換成立
- TODO 作成者 = MEMBER（MANAGE_JOBS なし）は 403
- 報酬額未入力で変換試行 → 400 バリデーション
- 変換成立後、`todos.job_posting_id` / `is_jobber_recruiting = TRUE` 更新、`job_postings.source_todo_id` 設定
- 二重変換 → 409
- TODO 削除（応募ゼロ） → 求人も論理削除
- TODO 削除（応募あり） → 409 + 「先に求人をキャンセルしてください」メッセージ
- 求人削除 → `todos.job_posting_id = NULL`, `is_jobber_recruiting = FALSE`

#### 16.6.7 E2E（Playwright）第三版シナリオ

- **Jobber 招待 E2E**: ADMIN が招待 → Jobber が受諾 → チームに JOBBER ロールで加入 → `JOBBER_INTERNAL` 求人応募成立
- **総合掲示板 E2E**: Jobber が opt-in → 掲示板で求人検索 → フィルタ適用 → 応募 → 採用 → QR チェックイン → エスクロー 7 日 → 早期 release
- **運営確定 E2E**: 10 名募集求人作成 → 当日運営が全員分の業務時間入力 → 各 Worker 承認 → エスクロー → 7 日経過自動 capture
- **TODO → 求人 E2E**: ADMIN が TODO 作成 → フラグ ON → モーダルで詳細補完 → 求人公開 → 応募 → 完了

### 16.7 パフォーマンステスト（第三版追加）

- **Jobber 総合掲示板**: 10,000 件の公開求人 + 絞り込みクエリで 500ms 以内
- **EscrowAutoCaptureJob**: 1,000 件の HOLDING 対象を 30 秒以内に処理
- **運営一括入力**: 50 名分の time_confirmation INSERT が 3 秒以内

---

## 17. 関連ファイル一覧

### 17.1 既存設計書との関係

| 設計書 | 関係 |
|--------|------|
| F01.1 認証 | ユーザー認証基盤（`users` 参照） |
| F01.2 組織・チーム・メンバー・ロール | `teams` / `organizations` / `memberships` 参照、`deputy_admin_permissions.MANAGE_JOBS` 追加 |
| F01.7 カスタム公開範囲テンプレート | `visibility=CUSTOM_TEMPLATE` 時に参照 |
| F03.11 募集型予約 | 募集→申込フローの参考（決済・契約追加） |
| F04.2 チャット | `job_contracts.chat_room_id` 連携 |
| F04.3 プッシュ通知 | JOB_* 通知タイプ追加 |
| F04.5 モデレーション | 個人連絡先交換検出 |
| F04.9 確認通知システム | 親権者同意で流用 |
| F08.2 支払い管理 | `stripe_customers` 共有（チーム年会費とは別テーブル `stripe_connect_accounts`） |
| F08.6 予算会計 | Mannschaft 側収益計上と接続（将来） |
| F10.3 監査ログ | `audit_logs` に JOB_* イベント追加 |
| F11.1 PWA | オフライン応募下書き |
| F11.3 i18n | 新規 `jobs.json` ロケール追加 |
| F12.1 PDF 生成 | 業務委託契約書 PDF |
| F12.3 GDPR | データ削除要求対応 |

### 17.2 新規作成ファイル

```
backend/src/main/java/com/mannschaft/app/jobmatching/
  entity/
    JobPosting.java, JobApplication.java, JobContract.java,
    JobCheckIn.java, JobQrToken.java,
    JobPayment.java, JobReview.java, StripeConnectAccount.java,
    JobNotificationPreference.java, JobMinorConsent.java, JobDisputeCase.java,
    JobberProfile.java,           ← 第三版新規
    JobberTeamInvitation.java,    ← 第三版新規
    JobTimeConfirmation.java      ← 第三版新規
  repository/（同上、第三版で +3 ファイル）
  service/
    JobFeeCalculator.java, JobPostingService.java, JobApplicationService.java,
    JobContractService.java, JobPaymentService.java, StripeConnectService.java,
    JobQrTokenService.java,
    JobCheckInService.java,
    JobHistoryService.java,
    GeolocationService.java,
    JobReviewService.java, JobDisputeService.java, JobNotificationService.java,
    MinorConsentService.java,
    JobberProfileService.java,      ← 第三版新規
    JobberInvitationService.java,   ← 第三版新規
    JobberPublicBoardService.java,  ← 第三版新規（総合掲示板フィルタ・位置検索）
    JobberPublicBoardNotifier.java, ← 第三版新規（@Scheduled 新着通知）
    JobTimeConfirmationService.java,← 第三版新規
    JobEscrowService.java,          ← 第三版新規（エスクロー状態管理・早期 release）
    TodoToJobPostingService.java,   ← 第三版新規
    JobVisibilityPolicy.java        ← 第三版新規（visibility_scope ベースの権限統合）
  controller/
    JobPostingController.java, JobApplicationController.java,
    JobContractController.java,
    JobQrTokenController.java,
    JobCheckInController.java,
    JobHistoryController.java,
    JobReviewController.java,
    JobDisputeController.java, StripeConnectController.java,
    JobFeeController.java, JobNotificationPreferenceController.java,
    JobberController.java,              ← 第三版新規
    JobberInvitationController.java,    ← 第三版新規
    JobberProfileController.java,       ← 第三版新規
    JobPublicBoardController.java,      ← 第三版新規
    JobTimeConfirmationController.java, ← 第三版新規
    JobEscrowController.java,           ← 第三版新規
    TodoConversionController.java       ← 第三版新規（既存 TodoController に追加でも可）
  webhook/
    PlatformStripeWebhookController.java, ConnectStripeWebhookController.java,
    handler/ … （各イベント個別ハンドラ + 第三版で payment_intent.amount_capturable_updated / payment_intent.canceled authorization 失効用）
  dto/ … （Request / Response DTO 約 50 ファイル、QR / CheckIn / History / Jobber / PublicBoard / TimeConfirmation / Escrow / TodoConversion 含む）
  config/
    StripeConnectConfig.java, JobQrSigningConfig.java
  policy/
    JobPolicy.java, JobHistoryPolicy.java,
    TeamRolePolicy.java          ← 第三版新規（JOBBER の feature ホワイトリスト管理）
  batch/
    PaymentReconciliationJob.java, AutoAcceptOverdueContractsJob.java,
    MinorConsentExpiryJob.java,
    JobQrTokenCleanupJob.java,
    GeolocationPurgeJob.java,
    CheckoutMissingAlertJob.java,
    EscrowAutoCaptureJob.java,           ← 第三版新規（7 日経過自動 capture）
    JobTimeConfirmationAutoApprovalJob.java, ← 第三版新規（72 時間自動承認）
    JobberInvitationExpiryJob.java       ← 第三版新規（招待期限切れ処理）

backend/src/main/resources/db/migration/
  V13.000__create_stripe_events.sql,
  V13.001__create_job_postings.sql  ～  V13.009__create_job_dispute_cases.sql,
  V13.010__create_job_qr_tokens.sql,
  V13.011__create_job_check_ins.sql,
  V13.012__alter_job_contracts_add_checkin_cols.sql,
  V13.013__create_v_worker_team_history.sql,
  V13.020__create_jobber_memberships_setup.sql,           ← 第三版（F00.5 以降は memberships ベース。旧 team_members ENUM 拡張は不要）
  V13.021__create_jobber_profiles.sql,                    ← 第三版
  V13.022__create_jobber_team_invitations.sql,            ← 第三版
  V13.023__create_job_time_confirmations.sql,             ← 第三版
  V13.024__alter_job_payments_add_escrow.sql,             ← 第三版
  V13.025__alter_job_postings_v3.sql,                     ← 第三版（visibility_scope リネーム + 新カラム）
  V13.026__alter_todos_add_job_flag.sql,                  ← 第三版
  V13.027__alter_job_dispute_cases_v3.sql                 ← 第三版

frontend/app/pages/jobs/
  index.vue, [id].vue, new.vue, manage.vue,
  public-board.vue                     ← 第三版新規（Jobber 総合掲示板）
frontend/app/pages/contracts/
  index.vue, [id].vue,
  [id]/qr.vue,
  [id]/scan.vue,
  [id]/confirm-time.vue,               ← 第三版新規（運営側業務時間確定）
  [id]/time-approval.vue               ← 第三版新規（Worker 承認画面）
frontend/app/pages/teams/[teamId]/jobs/
  history.vue
frontend/app/pages/teams/[teamId]/
  jobbers/index.vue,                   ← 第三版新規（Jobber 管理一覧）
  jobbers/invite.vue                   ← 第三版新規（招待モーダル / 専用ページ）
frontend/app/pages/jobber-invitations/
  accept.vue                           ← 第三版新規（招待受諾画面）
frontend/app/pages/me/jobs/
  history.vue
frontend/app/pages/me/
  jobber-profile.vue,                  ← 第三版新規（Jobber プロフィール編集）
  contracts/pending-approvals.vue      ← 第三版新規（一括承認 UI）
frontend/app/pages/me/stripe-connect/
  index.vue, return.vue, refresh.vue
frontend/app/components/jobs/
  JobFeePreview.vue, JobStatusBadge.vue, JobCard.vue,
  ConnectStatusIndicator.vue, MinorConsentForm.vue,
  JobApplicationForm.vue, JobCompletionReportForm.vue,
  JobDisputePanel.vue,
  QrCheckInDisplay.vue,
  QrScanner.vue,
  WorkerHistoryPanel.vue,
  JobHistoryTable.vue,
  InternalReviewMemoForm.vue,
  JobberInviteButton.vue,            ← 第三版新規
  JobberInvitationAcceptCard.vue,    ← 第三版新規
  JobberProfileForm.vue,             ← 第三版新規
  PublicBoardFilterSidebar.vue,      ← 第三版新規
  PublicBoardJobCard.vue,            ← 第三版新規
  EscrowStatusBadge.vue,             ← 第三版新規
  EscrowEarlyReleaseButton.vue,      ← 第三版新規
  TimeConfirmationForm.vue,          ← 第三版新規（単体 + CSV 一括）
  TimeConfirmationApprovalCard.vue,  ← 第三版新規
  TodoJobberFlagToggle.vue,          ← 第三版新規
  TodoToJobPostingModal.vue,         ← 第三版新規
  TodoJobberBadge.vue                ← 第三版新規
frontend/app/composables/
  useJobFee.ts, useJobPostings.ts, useJobApplications.ts,
  useJobContracts.ts, useStripeConnect.ts,
  useJobQrScanner.ts,
  useJobHistory.ts,
  useJobCheckInOffline.ts,
  useJobberProfile.ts,               ← 第三版新規
  useJobberInvitations.ts,           ← 第三版新規
  useJobPublicBoard.ts,              ← 第三版新規
  useJobEscrow.ts,                   ← 第三版新規
  useJobTimeConfirmation.ts,         ← 第三版新規
  useTodoToJobConversion.ts          ← 第三版新規（F11.1 offlineQueue 連携）
frontend/app/types/
  jobs.ts, stripeConnect.ts,
  jobCheckIn.ts,
  jobHistory.ts,
  jobber.ts,                         ← 第三版新規（JobberProfile, Invitation 型）
  jobEscrow.ts,                      ← 第三版新規
  jobTimeConfirmation.ts             ← 第三版新規
frontend/app/locales/{ja,en,zh,ko,es,de}/jobs.json
  （第三版で約 60 キー追加: jobber.invite.*, jobber.accept.*, publicBoard.filter.*, escrow.*, timeConfirmation.*, todoConversion.* など）
```

### 17.3 修正ファイル

- `backend/src/main/java/com/mannschaft/app/auth/enum/DeputyPermission.java` — `MANAGE_JOBS` 追加
- `backend/src/main/java/com/mannschaft/app/notification/enum/NotificationType.java` — `JOB_*` タイプ追加（`JOB_CHECKED_IN` / `JOB_CHECKED_OUT` / `JOB_CHECKOUT_MISSING` / `JOB_GEO_ANOMALY` / `JOB_REVIEW_LOGGED` 含む、**第三版**で `JOB_PUBLIC_BOARD_MATCH` / `JOB_TIME_CONFIRMATION_REQUESTED` / `JOB_TIME_CONFIRMED_BY_WORKER` / `JOB_EARLY_RELEASE_COMPLETED` / `JOB_ESCROW_CAPTURED` / `JOB_REAUTHORIZATION_REQUIRED` / `JOB_JOBBER_INVITATION` / `JOB_JOBBER_ACCEPTED` / `JOB_JOBBER_INVITATION_ACCEPTED` / `JOB_JOBBER_INVITATION_DECLINED` / `JOB_TODO_CONVERTED` 追加）
- `backend/src/main/resources/application.yml` — `mannschaft.fee.*` / `stripe.api-version` / `stripe.webhook-secret-*` / **`mannschaft.jobs.qr.signing-secret`** / **`mannschaft.jobs.qr.ttl-seconds`** / **`mannschaft.jobs.geolocation.encryption-key`** / **`mannschaft.jobs.escrow.window-days=7`** / **`mannschaft.jobs.escrow.safety-margin-hours=2`** / **`mannschaft.jobs.time-confirmation.auto-approval-hours=72`** / **`mannschaft.jobs.jobber-invitation.ttl-hours=72`** 追加
- `frontend/app/layouts/default.vue` — ナビゲーションに「スキマバイト」追加。**JOBBER ロールログイン時はタイムライン・TODO・議事録を非表示**（条件分岐）
- `frontend/app/types/visibility.ts` — `visibility_scope` enum を `TEAM_MEMBERS` / `TEAM_MEMBERS_SUPPORTERS` / `JOBBER_INTERNAL` / `JOBBER_PUBLIC_BOARD` / `ORGANIZATION_SCOPE` / `CUSTOM_TEMPLATE` に更新（旧 `TEAM_MEMBERS_ONLY` / `TEAM_MEMBERS_AND_SUPPORTERS` はリネーム）
- **`frontend/app/composables/useOfflineQueue.ts`** — F11.1 既存キューに `jobCheckIn` レコードタイプ + dispatcher 追加、**第三版で `todoToJobConversion` タイプも追加**
- **`frontend/app/sw.ts` / Service Worker エントリ** — Background Sync イベントの `jobCheckIn-sync` / **`todoToJobConversion-sync` タグ登録**
- **`frontend/app/types/team.ts`** — `TeamMemberRole` enum に `JOBBER` 追加（第三版）
- **`frontend/app/types/todos.ts`** — `Todo` インターフェースに `job_posting_id` / `is_jobber_recruiting` 追加（第三版）
- **`frontend/app/types/jobs.ts`** — `JobPosting` に `visibility_scope` / `time_confirmation_method` / `use_qr_check_in` / `source_todo_id` / `auto_join_jobber_on_apply` 追加
- **`backend/src/main/java/com/mannschaft/app/todo/entity/Todo.java`** — `jobPostingId`, `isJobberRecruiting` フィールド追加（F02.5 所管、第三版で連動変更）
- **`backend/src/main/java/com/mannschaft/app/team/entity/TeamMember.java`** — `role` enum に `JOBBER` 追加（F01.2 所管）
- `docs/features/F04.3_push_notification.md` — JOB_* 通知種別追記（チェックイン／アウト関連 + **第三版 Jobber/エスクロー/TODO 変換関連**）
- `docs/features/F01.2_org_team_member_role.md` — `MANAGE_JOBS` 権限追記 + **`JOBBER` ロール追記**
- **`docs/features/F02.5_action_memo.md`**（または TODO 関連設計書） — `todos.job_posting_id` / `is_jobber_recruiting` 追加、変換フローを追記
- `docs/features/F11.1_pwa_offline.md` — `jobCheckIn` オフラインキュータイプ登録の追記 + **`todoToJobConversion` キュータイプ追記**
- `docs/features/F12.3_gdpr_privacy.md` — 位置情報（`job_check_ins.geolocation_*`）の取り扱い・90日自動削除を追記 + **JOBBER 退会時の `jobber_profiles` / `jobber_team_invitations` データ扱い追記**
- **`docs/features/F04.9_confirmable_notification.md`** — JOBBER 招待・運営側時間確定承認での流用シナリオ追記

---

## 18. ステータス

🟡 **設計完了・実装待ち**

- マスターの要件確認 ✅（手数料率・決済方式・資金非保有方針）
- マスター追加要件（第二版） ✅（QR チェックイン／アウト方式・評価公開削除・履歴ダッシュボード + 再応募時過去履歴参照）
- **マスター追加要件（第三版） ✅**（JOBBER ロール新設・Jobber 総合掲示板・7 日エスクロー・大規模募集運営確定フロー・TODO → 求人自動転換）
- 設計書フリーズ 🔄（本ドキュメント **第三版** でフリーズ）
- 軍議未実施
- 実装未着手

---

## 19. 未解決問題

### 19.1 1 回目精査での指摘と解決

| 指摘事項 | 解決策 |
|---------|--------|
| 業務報酬の下限が低すぎて Worker 手数料の比率が大きくならないか | §3.6 で最低 500 円に設定（Worker 受取 390 円確保） |
| 手数料計算の端数処理（小数点切り捨て/四捨五入）が未定義 | §3.1 で `ROUND_HALF_UP` を明示 |
| PaymentIntent の capture_method が未定義 → 業務完了前に資金拘束できるか不明 | §2.6 / §8.3 で `capture_method=manual` を明記、事前オーソリ → 完了承認で capture |
| 同時応募時の排他制御仕様が曖昧 | §6.2 / §9.1 で `SELECT ... FOR UPDATE` + `GET_LOCK` + 楽観的ロック 3 層を明記 |
| Stripe Webhook の冪等性保証が未定義 | §8.5 で `stripe_events` 共通テーブル + `job_payments.webhook_event_ids` の二重管理 |
| 評価の片方のみ送信時の公開タイミング | ~~§2.4 / §11.6 で 14 日後自動公開と定義~~ → **第二版で撤回**: §19.4 の通り公開評価そのものを廃止し、内部記録に一本化（§11.6 参照） |
| 未成年 Worker の親権者同意フロー | §4.4 / §12.8 で F04.9 流用、`job_minor_consents` テーブル新設 |
| 危険作業カテゴリの未成年除外 | §4.4 / §12.8 で `is_dangerous` フラグ + UI フィルタ |
| CSRF 対策（Stripe Connect OAuth リダイレクト） | §10.5 で `state=UUID`（DB+TTL 15 分）検証を明記 |
| アクセシビリティ（WCAG 2.1 AA） | §9.3 で明示、`aria-live` の手数料プレビュー |
| i18n 6 言語対応 | §9.4 で `jobs.json` ロケール追加方針 |

### 19.2 2 回目精査での指摘と解決

| 指摘事項 | 解決策 |
|---------|--------|
| Stripe API バージョン固定方針が曖昧 | §8.7 で環境変数固定 + 段階リリース + Webhook 複数エンドポイント方式 |
| 手数料率変更時の既存契約処理 | §14.2 で `job_contracts` にスナップショット列保持（既存契約は凍結） |
| Stripe 手数料の実確定タイミングと DB 反映 | §3.5 / §8.8 で `balance_transaction` 確定後 `charge.updated` Webhook で更新 |
| Webhook 遅延時のリコンシリエーション | §8.8 で 15 分ごと `PaymentIntent.retrieve` 同期 + 日次バッチ |
| Requester が大口（50k 円超）の場合の 2 段階認証 | §10.3 で追加 |
| 返金ポリシー状態別テーブル | §11.4 で網羅 |
| 紛争時の PaymentIntent オーソリ有効期限 7 日対応 | §11.5 で仲裁 SLA 5 営業日と連動 |
| 監査ログと Stripe Event / job_payments の 3 冗長整合性検査 | §14.3 で日次バッチ |
| データ保持期間（7 年 / 5 年 / 10 年） | §14.4 で法人税法・民法・紛争長期考慮を明示 |
| 資金決済法・労働派遣法・下請法・特商法・景表法の再確認 | §12.1 〜 §12.6 でカバー |
| マイナンバー取得しない方針の明確化 | §10.7 / §12.7 で明示、取得責任は Requester 側へ |
| インボイス制度対応（2026 年時点） | §3.4 で明記、Mannschaft が適格請求書発行事業者登録 |
| 免税事業者時の消費税 OFF スイッチ | §3.4 / §14.2 で `fee.tax-enabled` フラグ化 |
| 業務委託契約書の自動生成（下請法対応） | §12.3 / §12.10 で F12.1 流用 |

### 19.3 3 回目精査（実装構造突合）の指摘と解決

| 指摘事項 | 解決策 |
|---------|--------|
| `stripe_events` 共通テーブルが本設計内で定義されていない | §8.5 で「共通テーブル」とのみ記述 → **このテーブルは F08.2 Phase 4 以降 + F13.1 で共通的に使うため、F13.1 V13.000 番台の前に V13.000 で先に作成する。カラム: `id BIGINT AI`, `stripe_event_id VARCHAR(100) UNIQUE`, `event_type VARCHAR(100)`, `processed_at DATETIME`, `payload_hash VARCHAR(64)`, `created_at DATETIME`。F08.2 既存設計側にも追記義務**（既存の `stripe_customers` と併用で Webhook 冪等基盤を共通化する）|
| `job_postings.visibility` に `PUBLIC` がない理由の参照が曖昧 | §2.1 に「労働者派遣法・税務コンプライアンス観点で禁止」と明記済み。§12.2 と相互参照ラベル追加 |
| `custom_visibility_templates` への FK 方向（F01.7 未マージ時の対応） | §5.2 / §17.3 で F01.7 が先行実装完了していることを前提とする。未完了の場合 Phase 13.1 着手前に F01.7 完了を待つ（軍議時にマイルストーン明記） |
| `chat_rooms` テーブル定義との整合（F04.2 既存） | §5.2 `job_contracts.chat_room_id` は既存 `chat_rooms.id` への FK（ON DELETE SET NULL）。軍議時に F04.2 の ON DELETE 既存制約を確認 |
| `notifications.type` enum への JOB_* 追加時のマイグレーション影響 | §17.3 `NotificationType.java` 修正だが、DB 側は ENUM ではなく VARCHAR(50) 想定（F04.3 で確認済み）のため ALTER 不要。Backend 側に追記のみ |
| `audit_logs` へのイベント追加時のスキーマ影響 | F10.3 `audit_logs.event_type` が VARCHAR のため、アプリ側の enum 追加のみで対応可 |
| Flyway V 番号の衝突確認 | V13.000 〜 V13.010 を予約。既存は V3.x / V4.x / V11.x / V12.x が使用中。V13 系は未使用のため OK。軍議着手前に最新 main で再確認 |
| 消費税計算の算出基準：Requester 手数料部分のみ課税 or Requester 支払総額に課税か | §3.2 で **「Requester 手数料部分」のみ課税** を明示。業務報酬部分は業務委託取引の当事者（Requester→Worker）取引のため Mannschaft の課税取引ではない |
| Stripe `on_behalf_of` 指定の税務整合性 | §8.3 で `on_behalf_of` により Stripe ダッシュボード・税務処理上 Worker 売上として扱う旨明記 |
| 業務委託契約書 PDF 生成タイミング | §12.3 / §17.2 で MATCHED 時に F12.1 を呼び出し。UI: 契約詳細からダウンロード可 |
| 72 時間 Connect 未完了自動キャンセルのジョブ実装 | §15.2 / §17.2 `JobContractService` に `cancelUnonboardedContracts()` + `@Scheduled` バッチ（1 時間毎）で実装 |
| 7 日自動承認バッチ | 同上 `AutoAcceptOverdueContractsJob.java`（§17.2）。`completion_reported_at + 7日` 経過で承認 |
| 未成年親権者同意の有効期限 1 年バッチ | 同上 `MinorConsentExpiryJob.java`（§17.2）。毎日実行 |
| 2 段階認証（50k 円超）の具体実装 | §10.3 で明示。軍議時に SMS OTP 基盤（既存 F01.1 の MFA 基盤）利用決定 |
| `job_payments.webhook_event_ids` の JSON 肥大化対策 | 最大 100 件までに制限し、それを超えたら古い方から削除（監査は `stripe_events` 側で永続） |
| Mannschaft 粗利計算での税抜 / 税込の扱い | §3.5 の `application_fee_amount` は税込手数料 + 消費税預かり分を合わせる。税納付義務分は `platform_consumption_tax_hold_jpy` で別管理（仕訳は F08.6 予算会計で処理、運用ダッシュボードは税込 / 税抜を切替表示） |
| `application_fee_amount` の算出誤り（初版）| **根治**: 初版では `requester_fee + requester_fee_tax + worker_fee - stripe_processing_fee` と記述したが、これは誤り。Stripe は platform の balance から決済手数料を別途差し引くため、`application_fee_amount` から減じると Worker 送金額が不足する。正しくは `requester_fee + requester_fee_tax + worker_fee`（§3.5 / §8.3 を修正済） |
| 粗利の税別 / 税込の定義統一 | マスター提示表との整合を取り、「粗利（税別）」と「粗利（税込、= application_fee_amount）」を両方定義。API レスポンスも `platform_gross_margin_excl_tax_jpy` と `platform_consumption_tax_hold_jpy` を分離出力（§6.2 修正済） |

### 19.4 第二版（2026-04-21 マスター追加指示）で発生した論点と解決

マスター追加指示「QR チェックイン／アウト方式」「評価公開を内部記録化」「履歴管理・再応募時過去履歴参照」の 3 点反映に伴う新規論点と解決。

| 指摘事項 | 解決策 |
|---------|--------|
| **QR トークンのリプレイ攻撃対策は十分か** | §10.10 で **TTL 60 秒（上限 5 分）+ nonce UNIQUE + HMAC-SHA256 署名 + `used_at` 使い捨て + 30 秒ごとの自動ローテーション** の 4 層防御を明記。古いスクリーンショットは自動的に無効化される |
| **署名鍵のローテーション方法** | §10.10 で `kid`（鍵 ID）を JWT 互換で埋め込み、複数鍵を並行検証可能に。新鍵投入 → 旧鍵は TTL 経過で自然消滅 |
| **Geolocation の保管期間・暗号化** | §10.10 / §12.11 で **AES-256-GCM 暗号化 + 契約完了後 90 日自動削除 + Worker 退会時即時削除** を明記。閲覧権限も Requester / 同一チーム ADMIN / SYSTEM_ADMIN のみに限定 |
| **Geolocation 拒否時の挙動（同意しないユーザー）** | §10.10 / §12.11 で **拒否でもチェックイン自体は成立**（位置情報なし運用も可）、Requester に「位置情報なしで成立」と表示する設計を明記。個人情報保護法上の拒否権を尊重 |
| **GPS 偽装への耐性** | Geolocation は補助情報とし、主要な不正防止は QR トークンの TTL + 使い捨てに委ねる。500m 乖離は警告のみで自動拒否しない（GPS 精度問題を考慮）。§10.10 に明記 |
| **複数チーム所属 Worker の履歴分離** | §2.8.4 / §10.10 で「**チーム A の ADMIN はチーム B での Worker 履歴を絶対に見られない**」を厳密に規定。`JobHistoryPolicy.canViewTeamHistory` + `canViewWorkerHistoryInTeam` の 2 段階権限チェック |
| **Worker 退会時の履歴データ扱い** | §12.9 で **`job_contracts` は法人税法 7 年保持のため `worker_user_id` を匿名化 + `worker_display_name_snapshot=NULL`**、位置情報は即時削除、内部評価メモは reviewee を匿名化して本文維持、という段階別ポリシーを明記 |
| **QR 読取失敗時のフォールバック** | §11.1.1 で **10 秒タイムアウト後に「手動コード入力」タブへ誘導** + Requester 画面の 6 桁 `short_code` を口頭伝達可。スキャナー非対応ブラウザは `@zxing/browser` フォールバック |
| **オフラインチェックイン時の TTL 扱い** | §9.5 で `scanned_at` が `issued_at` 〜 `expires_at` 範囲内であれば受け付ける設計。オンライン復帰時にサーバー側で `offline_submitted=TRUE` フラグ + `nonce` UNIQUE で重複弾き |
| **同一 Worker 複数契約の同時刻チェックイン衝突** | §2.3.1 / §6.2 で「掛け持ち禁止（同時刻別契約チェックイン時は 403）」を明記 |
| **再応募時パネルの権限境界（募集投稿者 DEPUTY_ADMIN の取り扱い）** | §2.8.2 で「チーム ADMIN + `MANAGE_JOBS` DEPUTY + 募集投稿者本人」に限定。投稿者が MEMBER 昇格前の DEPUTY だった場合の閲覧権限も `MANAGE_JOBS` に紐付けて統一 |
| **内部評価メモの Worker 本人閲覧範囲** | §5.2 / §2.4 で `visibility_scope` ENUM を追加し、`TEAM_ADMIN_ONLY`（Worker に見せない）/ `TEAM_ADMIN_AND_REVIEWEE`（Worker 本人にも見せる）を Requester が選択可能に。初期値は後者 |
| **CHECKED_IN → IN_PROGRESS 自動遷移のタイミング** | §5.4 備考で「チェックイン成立と同時に自動遷移、UI 上は統合表示」と明記。意味的な細分化として DB 状態は保持するが UX では意識させない |
| **緊急時の ADMIN 代理チェックイン（QR 読めない環境）** | §5.4 備考で論点として明示 → **解決策**: SYSTEM_ADMIN のみ `POST /api/v1/job-contracts/{id}/admin-override-checkin` を使用可（監査ログ `JOB_ADMIN_OVERRIDE_CHECKIN` で記録、レアケース用）。通常運用では使用しない |
| **履歴 CSV の個人情報漏洩対策** | §10.10 で `JOB_HISTORY_EXPORTED` 監査ログ記録必須 + 出力カラムから住所・電話番号等は除外（氏名・契約ID・金額のみ）。GDPR 応答時に「誰が何件出したか」追跡可能 |
| **履歴ダッシュボードのパフォーマンス** | §5.1 で `v_worker_team_history` ビュー + §5.2 で `idx_jc_team_completed` / `idx_jc_team_worker` インデックスを明記。初版は SELECT 集計、1000 件超で遅くなったらマテリアライズド化検討 |
| **Geolocation 暗号鍵の保管先** | §10.10 で KMS / HashiCorp Vault 相当に保管と明記。初期は環境変数（`mannschaft.jobs.geolocation.encryption-key`）で開始し、本番稼働前に Vault 統合を検討（軍議時に決定） |
| **既存 `work_started_at` との整合性** | §5.2 で `checked_in_at` と `work_started_at` を別列として保持（互換性優先）、通常は同時刻を記録。将来のマイグレーションで `work_started_at` 廃止を Phase 13.2 以降で検討 |
| **公開評価廃止による API 互換性** | 設計フリーズ前のため外部互換は考慮不要。旧設計の `is_published` / `published_at` / 「14 日自動公開」ロジックは全て削除（コード化されていないので影響なし） |

### 19.5 第三版（2026-04-21 マスター追加指示 5 点）で発生した論点と解決

マスター追加指示「JOBBER ロール新設」「Jobber 総合掲示板」「7 日エスクロー」「大規模募集運営確定フロー」「TODO → Jobber 募集自動転換」の 5 点反映に伴う新規論点と解決。

#### 19.5.1 1 回目精査（セキュリティ・UX・法務観点）

| 指摘事項 | 解決策 |
|---------|--------|
| **JOBBER が他チームのスキマバイトに参加した時の情報分離** | §10.11 / §2.9.3 で「マルチチーム所属 JOBBER でも各チーム情報は完全分離」を明記。ホワイトリスト方式 `TeamRolePolicy.canAccessTeamFeature()` + DB クエリ 3 層の強制 |
| **7 日エスクロー期間中の Requester 倒産リスク** | 資金は Stripe authorization hold に留まるため、Mannschaft も Requester も保有していない。Requester 倒産時は Stripe 側で hold が失効 → 再オーソリ不能なら `job_dispute_cases` で「Requester 支払不能」扱い → Worker 側に補償なし（§12.13 に法的整理を明記、Worker 側 FAQ にも記載）|
| **Stripe 障害時のエスクロー保全** | §8.9 / §13.2 で Resilience4j Circuit Breaker + 再試行 + 最終手動介入 SYSTEM_ADMIN アラートを明記 |
| **TODO 変換時の未完成項目（報酬額未入力等）の扱い** | §2.13.5 / §6.2 TODO 変換 DTO で「必須項目（work_end_at, base_reward_jpy）未入力時はバリデーション 400」。任意項目は後で `PATCH /api/v1/jobs/{id}` で追補可能。モーダル UI で未入力は送信 disable |
| **Jobber 総合掲示板での個人情報露出** | §10.13 で「Requester はチーム公開名のみ、応募者は氏名＋プロフィール公開セクションのみ」を規定。住所・電話は契約成立後にチャットで同意交換のみ |
| **JOBBER vs SUPPORTER の境界が UX で混乱しないか** | §11.7 で「無償 SUPPORTER vs 有償 JOBBER」を UI・ヘルプに明示。招待時点で 2 択 |
| **大規模募集での Worker 承認率低下（72 時間放置）** | §11.9 で多段階リマインダー (24h/48h/60h) + 自動承認で救済。§16.6.5 でテスト |
| **JOBBER が業務委託として独立と認められるか（偽装請負）** | §12.12 で「JOBBER は独立した業務委託先」「複数チーム可で専属性なし」を明記。規約 `/legal/jobber-agreement` + UI 注意喚起 |
| **7 日間預かりが Mannschaft 資金保有とみなされないか（資金決済法）** | §12.13 で「Stripe authorization hold は Stripe 資産（Stripe Payments Japan）、Mannschaft 非保有」を明記。DISPUTED_CAPTURED でも Stripe 残高であり Mannschaft 残高ではない |
| **総合掲示板で特商法表示が抜ける可能性** | §12.14 で「求人に必ず運営責任者表記リンクを併設」を強制 |
| **エスクロー期間中の Worker 退会リスク（報酬未受領のまま）** | §11.8 で「エスクロー中契約がある限り Worker 退会を UI ガード」。7 日経過で自動 capture → payout 完了まで解約不可 |
| **早期 release ボタンの二重押下・同時押下の競合** | §10.14 / §6.2 で「押下時に `early_release_*_approved_at IS NULL` を事前チェック → UPDATE、二重押下は 409」。同時押下は楽観的ロック `version` カラムで保護 |
| **異議申立期限切れ後の操作** | §10.14 で「`dispute_window_ends_at < NOW()` は 409」。UI 側も残り時間が 0 になったら disable |
| **総合掲示板の位置情報取扱い（フィルタ時）** | §10.13 で「位置フィルタクエリはサーバーログに残さない、IP + user_id のみ」を明記 |
| **JOBBER 招待メールのフィッシング耐性** | 招待 URL に `token_hash` ベースの平文トークン + TTL 72 時間。メール送信時点で DNS / DKIM / SPF 設定済みドメインから配信。受諾前にチーム情報 + 招待者氏名を画面表示してユーザーに確認させる |

#### 19.5.2 2 回目精査（保守性・一貫性・既存設計書との整合）

| 指摘事項 | 解決策 |
|---------|--------|
| **F02.5 TODO 機能への影響** | §5.2 / §17.3 で `todos.job_posting_id` + `is_jobber_recruiting` の追加を明示。F02.5 設計書にも連動追記。既存 TODO の CRUD ロジックには変更なし（新カラムはオプショナル） |
| **F04.3 通知機能との連携** | §17.3 で `JOB_*` 通知タイプを第三版で 11 種追加。`NotificationType` enum が VARCHAR 保存のため DB マイグレーション不要、アプリ側 enum 追加のみ |
| **F11.1 PWA オフライン中の TODO → Jobber 変換** | §17.3 で `useOfflineQueue` に `todoToJobConversion` タイプ追加。オフライン中にモーダル送信 → IndexedDB に下書き → オンライン復帰で `POST /api/v1/todos/{id}/convert-to-job-posting` 発火。Background Sync タグ `todoToJobConversion-sync` |
| **F01.2 JOBBER のメンバーシップ管理** | §5.2 / §17.3 で JOBBER は `memberships (role_kind='MEMBER') + jobber_profiles` で管理する方針に変更（F00.5 Phase 4 以降）。V13.020 による旧 `team_members.role` enum 拡張は不要となる |
| **F04.9 確認通知システムとの流用（Jobber 招待・時間確認）** | §2.9.2 / §2.12.4 で F04.9 `confirmable_notifications` を流用。§17.3 で F04.9 設計書への連動追記義務 |
| **既存 `visibility` カラムのリネーム影響** | §5.2 V13.025 で `CHANGE COLUMN visibility visibility_scope` による ENUM 拡張 + リネーム。frontend/types も `visibility` → `visibility_scope` に合わせる。既存レコードは `TEAM_MEMBERS_ONLY → TEAM_MEMBERS`, `TEAM_MEMBERS_AND_SUPPORTERS → TEAM_MEMBERS_SUPPORTERS` に自動マッピング（MySQL ENUM 順序維持） |
| **API 互換性: 既存クライアント（ないとはいえ）の `visibility` パラメータ** | 第三版で設計フリーズ前のため外部互換は考慮不要。実装時にバックエンド側で旧名称を受け入れる alias ハンドリング（JSON `@JsonAlias`）を入れておく選択肢あり |
| **Flyway V 番号の衝突確認** | V13.020 〜 V13.027 を第三版で予約。既存 V13.000 〜 V13.013 と衝突なし。V13.014 〜 V13.019 は将来の追加用に空けておく |
| **`job_contracts.status` ENUM に 4 状態追加の互換性** | §5.2 で既存の `MATCHED/CHECKED_IN/IN_PROGRESS/CHECKED_OUT/COMPLETION_REPORTED/COMPLETED/CANCELLED/DISPUTED` に加え `TIME_CONFIRMED/AUTHORIZED/CAPTURED/PAID` を追加。`COMPLETED` は互換維持。`JobContractStatusView.isCompleted()` で `CAPTURED / PAID / COMPLETED` を「完了扱い」として統合表示 |
| **運営確定方式の差し戻し時 version 履歴** | §5.2 で `(job_contract_id, version) UNIQUE`、差し戻し時は `REVOKED` → 新 version INSERT。履歴は残す |
| **バックエンド `JobPostingService` の visibility 分岐複雑化** | §17.2 `JobVisibilityPolicy` を新設し、visibility_scope 別の閲覧 / 応募権限判定を集約。`JobPostingService` は Policy 呼び出しのみ |
| **マイグレーション順序の依存関係** | V13.021 `jobber_profiles` は users 前提、V13.022 `jobber_team_invitations` は teams + users 前提、V13.023 `job_time_confirmations` は job_contracts 前提（既存）、V13.024 は job_payments 既存前提、V13.025 は job_postings + todos 前提（先に V13.026 で todos 拡張する必要があるかチェック）。**依存順**: V13.026 (todos 拡張) → V13.025 (job_postings FK for source_todo_id) として順序調整 |
| **JOBBER プロフィールの公開範囲と GDPR** | §12.9 に追記が必要 → 第三版で §17.3 に追記タスク反映。Worker 退会時に `jobber_profiles` カスケード削除、`jobber_team_invitations` の `invitee_user_id` 匿名化 |
| **Jobber 総合掲示板のパフォーマンス（10,000 件以上）** | §5.2 `idx_jp_public_board (visibility_scope, published_at)` インデックス、§16.7 で 500ms 以内のパフォーマンステスト規定。必要に応じて Redis キャッシュ（Valkey）+ TTL 5 分を導入（軍議時に決定） |
| **大規模運営確定方式での一括処理の整合性** | §2.12.3 で CSV 一括アップロード、バリデーションエラー時は 1 件でも失敗すればトランザクション全体ロールバック |
| **EscrowAutoCaptureJob のスケール（数万件）** | §8.9.5 で `FOR UPDATE SKIP LOCKED` を採用し、複数ノード並行実行可能に |
| **Jobber 招待取消後の再招待** | §6.1 で `REVOKED` 状態後に同一 invitee 再招待は可能（新トークン発行、旧トークンは検証失敗） |
| **TODO 変換と F02.5 Phase 3 拡張（既存）との衝突** | F02.5 Phase 3 でタイムライン投稿 + TODO 連動が実装済み（main マージ済）。Jobber 変換は Phase 3 の連動と独立させる（`is_jobber_recruiting` フラグは別概念）。既存の `is_scheduled_timeline_post` 等との排他制御は不要（両立可能） |

### 19.6 未解決問題（残）

**なし**。第三版 2 回精査までで全項目に解決策を提示済み。特に以下は根治設計として確定:

- **JOBBER の権限分離**: ホワイトリスト方式 `TeamRolePolicy` + DB クエリ 3 層で他チーム越権を物理的に不可能化
- **7 日エスクロー**: Stripe authorization hold 期間 7 日とちょうど整合、6 日 22 時間安全マージン + DISPUTED_CAPTURED 状態で期限オーバーも対応
- **資金非保有の法的整理**: Stripe 資産のまま、Mannschaft の資金移動業登録は不要（§12.1 + §12.13）
- **大規模募集 UX**: 10 名閾値で自動切替、72 時間承認タイムアウト + 多段階リマインダーで放置を防止
- **TODO 変換**: 権限チェック 2 層 + オフライン対応 + 二重変換防止
- **既存機能との整合**: F02.5 / F04.3 / F04.9 / F11.1 への連動追記義務を §17.3 で明記

軍議開始可能。

---

## 20. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-04-21 | 初版作成（全20章）|
| 2026-04-21 | 1 回目精査実施、11 項目を解決（§19.1） |
| 2026-04-21 | 2 回目精査実施、14 項目を解決（§19.2） |
| 2026-04-21 | 3 回目精査（実装構造突合）実施、14 項目を解決（§19.3）。未解決問題ゼロを確認 |
| 2026-04-21 | 計算検証で Stripe `application_fee_amount` 算出誤りを発見 → 根治修正（§3.2 / §3.3 / §3.5 / §6.2 / §8.3）。粗利の税別 / 税込定義を統一 |
| 2026-04-21 | ステータス「🟡 設計完了・実装待ち」に確定 |
| 2026-04-21 | **第二版**: マスター追加指示 3 点を反映 — (1) **QR コードチェックイン／アウト方式追加**（`job_check_ins` / `job_qr_tokens` 新設、状態遷移に `CHECKED_IN` / `CHECKED_OUT` 追加、オフライン対応、署名トークン）、(2) **評価公開システム削除**（内部記録化、同一チーム ADMIN + 本人のみ閲覧、星平均値削除）、(3) **履歴管理・再応募時過去履歴参照追加**（履歴ダッシュボード `/teams/{teamId}/jobs/history`、再応募時パネル、Worker マイページ履歴、CSV エクスポート、`v_worker_team_history` ビュー）。§2 / §5 / §6 / §7 / §9 / §10 / §11 / §12 / §15 / §16 / §17 を更新、§19.4 に 18 項目の論点と解決を記載 |
| 2026-04-21 | **第三版**: マスター追加指示 5 点を反映 — (1) **JOBBER ロール新設**（`team_members.role` enum 拡張、`jobber_profiles` / `jobber_team_invitations` 新設、招待フロー、複数チーム所属可、チーム通常活動アクセス制限）、(2) **募集スコープ 4 種化**（`visibility` → `visibility_scope` にリネーム、`JOBBER_INTERNAL` / `JOBBER_PUBLIC_BOARD` 追加、Jobber 総合掲示板 `/jobs/public-board` 新設、位置検索・絞り込み・新着通知）、(3) **7 日エスクロー**（`job_payments.escrow_status`/`dispute_window_ends_at`/`early_release_*` 追加、Stripe `capture_method=manual` 活用、`EscrowAutoCaptureJob`、早期 release / 異議申立 / DISPUTED_CAPTURED 状態）、(4) **大規模募集の運営確定方式**（`job_time_confirmations` 新設、`time_confirmation_method` enum、10 名以上強制、72 時間自動承認、多段階リマインダー）、(5) **TODO → Jobber 募集自動転換**（`todos.job_posting_id` + `is_jobber_recruiting`、TODO 変換 API、モーダル UI、PWA オフライン対応、ADHD 配慮で入力摩擦ゼロ）。§1 / §2 / §3 / §4 / §5 / §6 / §7 / §8 / §10 / §11 / §12 / §15 / §16 / §17 / §18 を更新、§19.5 / §19.6 に 32 項目の論点と解決を記載。Flyway V13.020 〜 V13.027 を予約 |
| 2026-04-21 | 第三版微修正: Worker 向け UI 文言「確認ボタン/確認依頼」を「承認ボタン/承認依頼」に統一（マスター指示）。enum/カラム/API/クラス名は互換性維持のため据え置き |
