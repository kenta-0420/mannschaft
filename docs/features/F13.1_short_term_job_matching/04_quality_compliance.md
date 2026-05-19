## 9. 非機能要件

### 9.1 パフォーマンス

- 求人一覧 API: 100 件まで 500ms 以内（インデックス活用）
- 手数料プレビュー API: 50ms 以内（キャッシュせず都度計算、JVMの軽量計算）
- **QR トークン発行 API**: 100ms 以内（HMAC 計算 + DB INSERT のみ）
- **チェックイン成立 API**: 200ms 以内（署名検証 + nonce lock + 状態遷移 + 通知発火。Geolocation 暗号化込み）
- **履歴ダッシュボード API**: 1000 件データで 500ms 以内（`idx_jc_team_completed` 活用、ページング 50 件）
- **CSV エクスポート**: 10000 件で 5 秒以内（ストリーミング出力、サーバー側メモリ保持しない）
- **第三版追加**:
  - **Jobber 総合掲示板 API**: 10,000 件の公開求人 + フィルタクエリで 500ms 以内。`idx_jp_public_board` + 地理検索（必要に応じて Haversine 簡易実装、PostGIS 相当は Phase 13.2 検討）
  - **Jobber 招待発行**: 100ms 以内（トークン生成 + INSERT + メール enqueue）
  - **EscrowAutoCaptureJob バッチ**: 1,000 件 HOLDING を 30 秒以内に処理（`FOR UPDATE SKIP LOCKED` で並列化）
  - **運営一括業務時間入力**: 50 名分の time_confirmation を 3 秒以内（バルク INSERT）
  - **Worker 承認 API**: 100ms 以内（状態遷移のみ）
  - **TODO → 求人変換**: 500ms 以内（権限チェック + INSERT + 通知配信）
- 同時応募排他制御:
  - 楽観的ロック（`version` カラム）
  - `SELECT ... FOR UPDATE` 行ロック（採用確定時）
  - `GET_LOCK` による Advisory Lock（job_posting 単位）
  - トランザクション分離レベル `READ_COMMITTED`（MySQL InnoDB デフォルト）
- **エスクロー排他制御（第三版）**:
  - 早期 release 押下: `UPDATE job_payments SET early_release_*_approved_at = NOW() WHERE id=? AND early_release_*_approved_at IS NULL` で冪等
  - 同時に両者押下時の競合: `SELECT ... FOR UPDATE` で両フラグが NOT NULL になったか判定し、成立時のみ capture 呼び出し

### 9.2 SEO

- 求人ページは **`<meta name="robots" content="noindex, nofollow">`** を付与（クローズドマーケット）
- sitemap.xml に含めない
- 認証ゲート越しのため検索インデックス対象外

### 9.3 アクセシビリティ

- WCAG 2.1 Level AA 準拠
- フォーム要素の `aria-label`・`aria-describedby`
- エラーメッセージは `role="alert"` でスクリーンリーダーに即時通知
- キーボード操作（Tab / Enter / Esc / Arrow）完全対応
- 色のみで状態を区別しない（色弱対応）
- 手数料プレビューは `aria-live="polite"` で読み上げ

### 9.4 i18n

6言語対応（ja/en/zh/ko/es/de）。新規追加キー:

```
frontend/app/locales/{lang}/jobs.json    ← 新規追加ファイル
```

主要キー: `job.title.required`、`job.fee.breakdown.requester_fee`、`job.status.matched`、`job.status.checked_in`、`job.status.checked_out`、`job.qr.scan_prompt`、`job.qr.offline_queued`、`job.history.export_csv`、`job.review.internal_only` 等。通貨表記はユーザーロケールに応じて `¥` or `JPY`。

### 9.5 オフラインチェックイン対応（F11.1 PWA 連携）

- **IndexedDB ストア**: 既存 `offlineQueue`（F11.1）に `jobCheckIn` レコードタイプを追加
  - スキーマ: `{ type: 'jobCheckIn', token | short_code, scanned_at, geolocation, contractId, retryCount }`
- **Service Worker**: オンライン復帰を Background Sync API で検知、キュー先頭から順次 `POST /api/v1/jobs/check-ins` に送信
- **サーバー側の受け入れ判定**:
  - `scanned_at` が `token.issued_at` 〜 `token.expires_at` 範囲内であれば受け付け（オフライン送信前提）
  - `offline_submitted=TRUE` を立てて監査
- **再送制御**: 最大 5 回リトライ、指数バックオフ（1 / 2 / 4 / 8 / 16 秒）
- **重複防止**: `nonce` UNIQUE 制約により 2 回処理は拒否（冪等性保証）
- **ユーザー告知**: オフラインで登録した場合は「📡 オフラインです。復帰後に自動送信します」トースト、復帰後に「✅ チェックインが確定しました」と通知

---

## 10. セキュリティ観点

### 10.1 カード情報の非保持（PCI DSS SAQ-A）

- Stripe Elements / Payment Element を利用（HTML iframe で Stripe ドメイン上にカード番号が入力される）
- Mannschaft サーバーはカード番号・CVC を一切受信しない
- SAQ-A（最小限の対応）で認定可能

### 10.2 Webhook 署名検証

- 全 Webhook エンドポイントで `Stripe-Signature` ヘッダー検証必須
- 検証失敗は即 400 を返し、DB 書き込みせず監査ログのみ残す
- Webhook Secret は環境変数 `STRIPE_WEBHOOK_SECRET_PLATFORM` / `STRIPE_WEBHOOK_SECRET_CONNECT` に分離

### 10.3 承認フロー（誤送金防止）

- 承認ボタン押下後に **確認ダイアログ**（「この金額を確定して支払います」）
- 大口取引（50,000 円超）は 2 段階認証（パスワード / SMS OTP）

### 10.4 SQL インジェクション・XSS 対策

- JPA / MyBatis パラメータ化クエリのみ使用
- Vue テンプレート `{{ }}` 使用でデフォルト XSS 対策
- Rich-text（業務内容 Markdown）は DOMPurify でサニタイズ

### 10.5 CSRF

- Spring Security CSRF トークン（HttpOnly Cookie）
- Stripe Connect Express onboarding return_url は **state=UUID（DB 保存 + TTL 15分）** を検証
- CSRF 対策を回避して呼べる API はなし（GET 以外は全て要トークン）

### 10.6 権限チェック

- `@PreAuthorize` + ポリシーサービスによる多層チェック
  - レベル1: ロール（`@PreAuthorize("hasRole('ADMIN')")`）
  - レベル2: リソース所有権（`JobPolicy.canEdit(currentUser, jobPostingId)`）
  - レベル3: 金額操作（capture / refund）は ADMIN 権限必須、さらに ADMIN 当事者であることを確認
- Controller・Service 両層で二重チェック

### 10.7 個人情報

- 住所・口座情報・マイナンバーは Stripe 側のみ保持（Mannschaft は `acct_xxx` の ID のみ）
- Mannschaft が保持する個人情報最小化:
  - Worker: `stripe_account_id`、`charges_enabled`、`payouts_enabled`
  - 親権者同意（`guardian_name`, `guardian_email`, `guardian_phone`）のみ、マイナンバーは取得しない
- **マイナンバー収集**: 年間 20 万円超の支払いに対する支払調書作成には原則マイナンバーが必要だが、**業務委託の場合は支払調書作成義務者の観点で Requester 側が取得責任を負う**。Mannschaft は仲介の立場に留まり、マイナンバーを取得しない（§12.6 で詳述）。

### 10.8 レートリミット

- `POST /api/v1/jobs/{id}/applications`: **1 ユーザー / 1 分 / 5 回**（スパム応募防止）
- `POST /api/v1/jobs`: 1 ユーザー / 1 時間 / 20 件
- `POST /api/v1/stripe/connect/onboarding-link`: 1 ユーザー / 1 時間 / 10 回
- Webhook: 10000 req/min（Stripe の burst に耐える）
- 実装: Bucket4j + Redis（Valkey）バックエンド

### 10.9 監査ログ

F10.3 `audit_logs` テーブルに以下のイベントを記録（保管期間 7 年）:

| イベント | 重要度 |
|---------|------|
| `JOB_POSTING_CREATED` | 通常 |
| `JOB_APPLICATION_ACCEPTED` | 高 |
| `JOB_QR_TOKEN_ISSUED` | 中（トークン nonce のみ、署名本体は記録しない）|
| `JOB_CHECK_IN_RECORDED` | 中（contract_id, type, scanned_at, geo_anomaly）|
| `JOB_CHECK_IN_REJECTED` | 中（失敗理由: expired / nonce_reused / wrong_worker / signature_invalid）|
| `JOB_PAYMENT_AUTHORIZED` | 高（金額付き） |
| `JOB_PAYMENT_CAPTURED` | 高（金額付き） |
| `JOB_PAYMENT_REFUNDED` | 高（金額・理由付き） |
| `JOB_DISPUTE_OPENED` | 高 |
| `JOB_DISPUTE_RESOLVED` | 高 |
| `JOB_INTERNAL_REVIEW_LOGGED` | 中（comment 本文はハッシュのみ、原文は `job_reviews`）|
| `STRIPE_CONNECT_ACCOUNT_STATUS_CHANGED` | 中 |
| `MINOR_CONSENT_GRANTED` | 高 |
| `JOB_HISTORY_EXPORTED` | 中（CSV エクスポートの actor / teamId / 件数を記録、GDPR 応答用）|
| **=== 第三版追加 ===** | |
| `JOB_JOBBER_INVITATION_CREATED` | 中（招待元・招待先・チーム ID）|
| `JOB_JOBBER_INVITATION_ACCEPTED` | 中 |
| `JOB_JOBBER_INVITATION_DECLINED` | 中 |
| `JOB_JOBBER_INVITATION_REVOKED` | 中 |
| `JOB_JOBBER_REMOVED` | 中（チームから JOBBER 削除） |
| `JOB_TIME_CONFIRMATION_CREATED` | 中（運営側時間入力、version 付き）|
| `JOB_TIME_CONFIRMATION_APPROVED_BY_WORKER` | 中 |
| `JOB_TIME_CONFIRMATION_DISPUTED` | 高（異議理由付き）|
| `JOB_TIME_CONFIRMATION_AUTO_APPROVED` | 中（72 時間タイムアウト）|
| `JOB_ESCROW_STARTED` | 高（完了承認時、金額付き）|
| `JOB_EARLY_RELEASE_REQUESTED` | 中（押下者・押下時刻）|
| `JOB_EARLY_RELEASE_COMPLETED` | 高（両者承認で capture 実行、金額付き）|
| `JOB_ESCROW_AUTO_CAPTURED` | 高（7 日経過自動 capture、金額付き）|
| `JOB_ESCROW_DISPUTED` | 高（異議申立）|
| `JOB_ESCROW_CANCELLED` | 高（authorization 失効）|
| `JOB_TODO_CONVERTED_TO_POSTING` | 中（TODO ID → 求人 ID）|

金銭が動く全イベントで `before_state` / `after_state` / `amount_jpy` / `actor_user_id` を記録。

### 10.10 QR トークン・Geolocation のセキュリティ

#### QR トークン署名・リプレイ攻撃対策

- **署名方式**: HMAC-SHA256 / 鍵は環境変数 `JOB_QR_SIGNING_SECRET`（256bit 以上）+ `kid` によるバージョン管理
- **TTL**: デフォルト 60 秒、設定上限 5 分（長すぎるとスクリーンショット撮影による不正のリスク）
- **nonce 使い捨て**: `job_qr_tokens.nonce` UNIQUE + `used_at` 一度のみ有効
- **自動ローテーション**: Requester 画面は `expires_at` - 5 秒前に次トークンを取得、常に最新 QR のみ有効（古いスクリーンショットを無効化）
- **3 層防御**:
  1. 署名検証（改ざん検知）
  2. `expires_at` 確認（時間切れ）
  3. `used_at` 確認（再利用検知）
- **鍵ローテーション**: `JOB_QR_SIGNING_SECRET_V2` を追加 → `kid` を `v2` にして新規発行 → 旧 `v1` は TTL 経過で自然消滅
- **監査**: 署名検証失敗は `JOB_CHECK_IN_REJECTED` で監査ログに記録（ブルートフォース検知）

#### カメラ権限・Geolocation 権限の説明 UI

- 初回スキャン時に**明示的な同意ダイアログ**を表示
  - カメラ: 「業務チェックインのために QR コード読取専用にカメラを使用します。画像は端末外に保存されません」
  - Geolocation: 「業務場所との照合のために位置情報を取得します。同一チーム ADMIN のみ閲覧可能、契約完了 90 日後に自動削除」
- **拒否時のフォールバック**:
  - カメラ拒否 → 手動コード入力タブへ自動切替、ユーザーに説明
  - Geolocation 拒否 → チェックイン自体は成立させるが `geolocation_*` は NULL、Requester に「位置情報なしで成立」と表示（拒否権を認める）
- **個人情報保護法対応**: 同意取得記録を `user_privacy_consents`（既存 F12.3 想定）に保存

#### Geolocation の保管ポリシー

- **暗号化**: AES-256-GCM（アプリ層で暗号化、鍵は KMS / HashiCorp Vault 等で保管）
- **保管期間**: 契約完了後 **90 日**で自動削除（`geolocation_deleted_at` を記録、`_latitude/_longitude/_accuracy_m` を NULL 更新）
- **削除バッチ**: `GeolocationPurgeJob` が毎日深夜実行
- **閲覧権限**: Requester 本人、当該チーム ADMIN（`MANAGE_JOBS`）、SYSTEM_ADMIN のみ
- **乖離検知**:
  - 業務場所と端末位置の Haversine 距離 > 500 m で `geo_anomaly=TRUE`
  - Requester に `JOB_GEO_ANOMALY` 通知（Worker には通知せず、Requester 判断に委ねる）
  - **自動拒否はしない**（GPS 精度低下・屋内誤差・地下等の誤検出を考慮）
- **プライバシーフレンドリー**: Requester 画面では緯度経度を直接表示せず、「業務場所から 800 m」などの距離のみ提示。緯度経度は監査ログ API 経由でのみ取得可能

#### 履歴ダッシュボード・再応募時パネルの権限境界

- **絶対条件**:
  - チーム A の Worker 履歴はチーム A の ADMIN のみ閲覧可
  - チーム B の ADMIN がチーム A の Worker 履歴を参照することは**絶対に不可**（リクエスト時の `teamId` と `X-Actor-User-Id` のメンバーシップを二重検証）
- **Service 層**: `JobHistoryPolicy.canViewTeamHistory(currentUser, teamId)` + `canViewWorkerHistoryInTeam(currentUser, teamId, workerId)` を Controller・Service の両層で呼び出し
- **CSV エクスポート監査**: エクスポート実行時に `JOB_HISTORY_EXPORTED` 監査ログ記録（誰がいつ何件出したか追跡）

### 10.11 JOBBER 権限分離（第三版新規）

- **原則**: JOBBER は「有償でスキマバイトに応募する助っ人」ロールであり、**チームの通常活動にアクセス権を持たない**
- **実装**: `TeamRolePolicy.canAccessTeamFeature(role, feature)` でホワイトリスト方式
  - `JOBBER` に許可される feature: `JOB_POSTINGS_VIEW_JOBBER_SCOPE` / `JOB_APPLICATIONS_SELF` / `JOB_CONTRACT_VIEW_SELF` / `JOB_CHECK_IN_SELF` / `CHAT_CONTRACT_ROOM_SELF` / `JOBBER_PROFILE_EDIT_SELF` / `PUBLIC_BOARD_VIEW_OPT_IN`
  - それ以外の feature は `403 Forbidden`
- **二重チェック**: Controller + Service + データベースクエリ（`WHERE` 句で強制）の 3 層
- **マルチチーム所属時の情報分離**: JOBBER が複数チームに所属する場合でも、各チームの情報は完全に分離
  - 例: JOBBER がチーム A とチーム B 両方に所属していても、`GET /api/v1/teams/{A}/jobs` と `GET /api/v1/teams/{B}/jobs` は互いの情報を含まない
  - 自分の契約詳細も、当該チーム単位で閲覧

### 10.12 TODO → 求人変換時の権限チェック（第三版新規）

- **最小権限**: TODO 作成者本人 + ADMIN/DEPUTY(`MANAGE_JOBS`) の両方を満たす場合のみ許可
  - TODO 作成者が後で DEPUTY 権限を剥奪された場合、以降は変換不可（既存求人は残るが新規変換不可）
- **変換時のバリデーション**:
  - `todos.job_posting_id IS NOT NULL` なら 409（二重変換防止）
  - TODO 作成チームの `team_id` と求人作成先 `team_id` が一致すること（別チームへの転送は不可）
- **CSRF 対策**: 通常の Spring Security CSRF トークンに加え、TODO → 求人変換はモーダル経由の意図的操作なので、モーダル表示時に短命 nonce を発行し変換 API コール時に検証

### 10.13 総合掲示板での個人情報露出（第三版新規）

- **Requester 情報**: チーム公開名 (`teams.public_name`) + アバター のみ露出
- **応募者情報**（Jobber 側から Requester が見る）:
  - 氏名 / アバター / Jobber プロフィールの公開セクション
  - Jobber プロフィールで `preferred_skills` / `display_headline` は公開可、住所詳細・電話番号は非公開
- **契約成立後**: チャット内で両者合意の上で連絡先を交換できる（F04.5 モデレーションルールに従う）
- **検索クエリの漏洩リスク**:
  - フィルタクエリ（`lat`/`lng`/`skills` 等）はサーバー側で集計・ログ残しせず、個別ユーザーの関心事象を追跡しない
  - ただしアクセスログには IP + ユーザー ID のみ記録（GDPR 対応の説明明書通り）

### 10.14 エスクロー決済時のセキュリティ（第三版新規）

- **早期 release の二重押下防止**: 各 payment_id について Requester / Worker それぞれ 1 回のみ押下可（`UNIQUE KEY (payment_id, role)` を持たない代わりに `early_release_*_approved_at IS NOT NULL` チェック）
- **異議申立の期限切れ**: `dispute_window_ends_at < NOW()` で提起された dispute は即 409 を返し成立させない（悪意的な期限切れ後申立を防ぐ）
- **Stripe 不整合時の資金凍結**: 早期 release 後に Stripe capture が失敗した場合、`escrow_status` を `HOLDING` に戻し、両者に通知。手動介入のために SYSTEM_ADMIN にもアラート

---

## 11. ユーザビリティ観点

### 11.1 初回オンボーディング摩擦低減

- 求人閲覧・応募時点では Connect 未登録でも可（気軽さ優先）
- **採用が「確定しそう」な段階で Worker に「支払い口座を登録しましょう」バナー**を出し、72 時間猶予を提示
- Connect 登録完了まで採用確定不可のため、Requester 側にも「この候補者は口座登録中」アイコンを表示し期待値を合わせる
- オンボーディング中に戻れる UI（ブックマーク可能なリンク）

#### 11.1.1 QR チェックインの摩擦低減

- **カメラ起動の遅延対策**:
  - 「スキャン」ボタン押下で即座に `getUserMedia` を呼ぶのではなく、契約詳細画面に入った時点で**権限照会**（`permissions.query({name:'camera'})`）を先行実行し、「許可済み」ならスキャン画面プリロード
  - 初回のみ同意ダイアログ、2 回目以降はタップから 500ms 以内にカメラ映像表示
- **読取成功率向上**:
  - QR 密度は Low/Medium を自動選択（短命トークンのため URL は短い）、エラー訂正率 L で十分
  - Worker 側スキャナーは BarcodeDetector API を優先、非対応ブラウザは `@zxing/browser` にフォールバック
  - 画面輝度を最大化する HTML5 `screen.wakeLock` + `<meta theme-color>` でコントラスト確保
- **失敗時の救済**:
  - スキャン 10 秒経っても成立しない場合、「手動コード入力に切り替える」ボタンを下部に表示
  - Requester 画面の 6 桁コードを口頭で伝える運用を公式にサポート
- **成立フィードバック**:
  - スキャン成功時: 触覚フィードバック（Vibration API、50ms）+ サウンド（`<audio>` beep）+ 画面「✅ チェックイン完了」
  - Requester 画面には WebSocket push で即座に表示（Worker の顔写真 + 時刻）

### 11.2 手数料の透明性

- 求人作成 / 応募 / 契約詳細のすべてで手数料内訳を表示
- 税込総額と手取り額を太字で強調
- 「なぜ手数料が必要か」説明リンク（/help/jobs/fee）
- 景表法観点で「実質無料」等の誤認誘発表現は禁止

### 11.3 業務中コミュニケーション

- 既存 F04.2 チャット流用（業務専用タグ付きルーム）
- チャット内で待ち合わせ・変更連絡
- 個人連絡先交換の検出時は F04.5 モデレーションで警告

### 11.4 キャンセル・返金ポリシー（状態別）

| 状態 | キャンセル可否 | 返金 | Requester 負担 |
|-----|-------------|------|--------------|
| DRAFT | 自由 | — | なし |
| OPEN（応募0件） | 自由 | — | なし |
| OPEN（応募あり） | 可（理由必須、応募者へ通知） | — | なし |
| MATCHED（業務開始 24h 超前） | 両者合意で可 | 全額返金（Stripe 手数料のみ Mannschaft 負担） | なし |
| MATCHED（業務開始 24h 以内） | Requester 責任 → 50% キャンセル料を Worker へ、50% 返金 | 50% 返金 | Stripe 手数料 |
| MATCHED（業務開始 24h 以内） | Worker 責任 → 全額返金 | 全額返金 | Stripe 手数料 |
| IN_PROGRESS | 紛争モードへ | — | — |
| TIME_CONFIRMED（第三版） | Worker 承認済みのため原則キャンセル不可、紛争モードへ | — | — |
| COMPLETION_REPORTED | 差し戻し 3 回まで、以降紛争 | — | — |
| **AUTHORIZED（第三版、エスクロー中）** | **7 日以内に異議申立 → 紛争モード。両者合意キャンセルで全額返金** | 合意時全額、異議時は仲裁 | Stripe 手数料（合意キャンセル時） |
| CAPTURED（第三版） | Stripe 返金プロセス必須。ADMIN 承認 + refund.create() | 部分／全額返金可 | Stripe 手数料は返却されない |
| PAID（第三版） | 原則返金不可（Worker 口座入金済み）。例外は紛争仲裁結果による | — | — |
| COMPLETED（旧互換） | 不可 | — | — |

### 11.5 紛争解決フロー

```
チャットで当事者解決 → 24h 改善なし
    ↓
サポート報告（ticket + /api/v1/job-contracts/{id}/disputes）
    ↓
ADMIN / SYSTEM_ADMIN が job_dispute_cases UNDER_REVIEW
    ↓
チャット履歴・完了報告・写真等を調査
    ↓
仲裁（Worker 勝ち / Requester 勝ち / Split）
    ↓
job_payments 操作（capture / refund / 部分返金）
    ↓
両者へ結果通知
```

- ADMIN 仲裁は SLA 5 営業日以内
- 仲裁中は PaymentIntent をオーソリ状態で保持（Stripe のオーソリ有効期間 7 日以内に決着しない場合は再オーソリか一時 capture が必要 → **仲裁 SLA 5 営業日を設定する理由**）

### 11.6 内部記録でチーム運営の質を向上（公開評価は廃止）

**設計方針の変更（マスター決定）**: 本機能は**チーム内コミュニティ限定**の業務委託であり、タイミー型の公開星評価による信頼醸成は必要ない。既にチームメンバー・サポーターという関係性で信頼が担保されているため、評価は**内部記録**として運用する。

- **記入主体**: Requester 側 ADMIN / DEPUTY(`MANAGE_JOBS`) のみ（Worker 側からの逆評価は Phase 13.2 で検討、当面なし）
- **閲覧範囲**: 同一チームの ADMIN・DEPUTY(`MANAGE_JOBS`) + 評価対象 Worker 本人（`visibility_scope` で細粒度設定可能）
- **星評価平均値の計算・プロフィール表示は行わない**（`average_rating` API を提供せず、フロントにも平均値コンポーネントを置かない）
- **公開タイミング**: 記入直後にスコープ内ユーザーは即閲覧可能（「14 日経過で自動公開」のロジックは**廃止**）
- **コメント**: 1000 字まで、マークダウン不可（プレーンテキストのみ、XSS リスク低減）
- **再応募時**: §2.8.2 のパネルで前回メモの冒頭 100 字をプレビュー表示（次回起用判断に活用）
- **低評価の扱い**: 連続する低評価（1〜2 評価）はチーム ADMIN のみが把握し、チーム内で対話的に解決。**自動 ADMIN 審査対象化は廃止**（外部プラットフォームでなく内部コミュニティであるため、運営自律に委ねる）
- **目的**: チーム運営のナレッジ蓄積・次回起用判断・改善フィードバックループの構築

> **なぜ公開廃止なのか**（ユーザーへの説明文案、ヘルプページ `/help/jobs/why-no-public-rating` に掲載）:
> 「Mannschaft のスキマバイトは、チームメンバー・サポーターという既に信頼関係のあるコミュニティ内の業務委託です。匿名の公開評価は、関係の浅い不特定多数を前提とする仕組みであり、顔の見える関係では不要です。記録はチーム運営のためだけに使われます。」

### 11.7 JOBBER vs SUPPORTER の境界明示（第三版）

ユーザーが「自分はどちらに登録するべきか」迷わないよう、以下の UX 説明を UI とヘルプに併記:

> **SUPPORTER（サポーター）**: チームを**無償で応援**するポジション。PTA 役員、保護者、OB/OG 等、チームとの継続的関係性があり、ボランティア前提で活動を手伝う方。
>
> **JOBBER（ジョバー）**: **有償でピンポイントに手伝う**ポジション。スキマ時間に報酬を得ながら手伝いたい方。タイムラインや議事録等、チームの日常活動にはアクセスせず、**スキマバイト関連の機能だけ**を使う。複数のチームから招待を受けて登録可能。

- **アイコン分離**: SUPPORTER は人型アイコン、JOBBER は財布 / ハンマーのアイコンで視覚的に区別
- **ADMIN 側の招待 UI**: 「無償で協力してもらう人 (SUPPORTER)」「有償で単発作業をお願いする人 (JOBBER)」を 2 択で選ぶフローで招待時点から明確化

### 11.8 7 日間エスクローの透明性（第三版）

- **Worker の不安を低減するため**、「預かり中」バッジで常に残り時間を可視化
- ヘルプページ `/help/jobs/escrow` に以下を記載:
  - 「7 日間は何のために？」— 万が一業務内容に食い違いがあった場合の話し合い期間。7 日何もなければ自動支払い
  - 「早期に受け取りたい」— Requester と同時にボタンを押せば即座に支払われる
  - 「異議が出た場合」— 運営が調停。結果に応じて全額・部分・返金のいずれか
- **通知**: 7 日目の 24 時間前に「まもなく自動支払いされます」リマインダー通知（Worker のみ）
- **Worker 離脱時の扱い**: エスクロー中の契約は Worker 退会申請を保留（契約完了 or キャンセルまで退会できない UI ガード）

### 11.9 大規模募集での Worker 承認 UX（第三版）

- **72 時間タイマーの視覚化**: 契約詳細で「残り X 時間 Y 分」を大きく表示
- **プッシュ通知の多段階リマインダー**:
  - 確定通知送信 → 24 時間後にリマインダー 1 → 48 時間後にリマインダー 2 → 60 時間後に最終警告
- **一括承認 UI**（Worker 側で複数契約を持つ場合）: `/me/contracts/pending-approvals` で全未承認を一覧表示 + チェックボックス複数選択 → 一括承認
- **異議提起のハードル下げ**: 異議フォームは「提示された時間と違う」「業務内容と違う」「金額が合わない」のプリセット選択 + 自由記述（300 字）

### 11.10 TODO → Jobber 募集の一貫体験（第三版）

- **フラグ ON からモーダル起動までの 1 タップ**: TODO 編集画面で「💼 Jobber 募集に切り替える」ボタン → モーダル即起動
- **モーダル内プレビュー**:
  - 左側: TODO 側の情報（title, description, due_date）
  - 右側: 自動補完された求人フォーム + 未入力項目の強調表示（報酬額・終了時刻など）
  - 手数料プレビューパネル（`<JobFeePreview>` 流用）でリアルタイム計算
- **保存と公開の分離**: モーダル送信は `DRAFT` 作成で止めるか `OPEN` 公開するかを選択可
- **TODO 一覧でのステータス表示**:
  - 未変換 + フラグ OFF: 通常表示
  - フラグ ON + 未変換: 🔶 橙色「Jobber 募集準備中」
  - 変換済 + 公開中: 💼 青色「Jobber 募集中」
  - 変換済 + 応募あり: 💼 緑色「応募 X 件」
  - 変換済 + 契約成立: 👷 紫色「業務進行中」
- **ADHD 配慮（ユーザー記憶参照）**: TODO 入力時点では最小限の情報のみ必要（title, due_date）、報酬等の詳細はモーダルに入ってからでよい。入力摩擦ゼロを保つ

---

## 12. 法務・コンプライアンス観点

### 12.1 資金決済法

- **Mannschaft は資金を一切保有しない**。Stripe が資金移動の当事者となるため、資金移動業の登録は不要
- Stripe Japan（Stripe Payments Japan 株式会社）が関東財務局登録の資金移動業者。Destination Charges の資金保有は Stripe が負う
- Mannschaft は「決済代行業者との接続サービス」を提供するに留まる

### 12.2 労働者派遣法

- 雇用契約ではなく **業務委託契約（請負型）** として運用
- Requester から Worker への指揮命令はなく、業務完了条件のみを定義する成果物ベース
- 派遣免許は不要。ただし以下の線引きを明確化:
  - 業務時間中の業務指示（業務完了条件内の範囲）は OK
  - 労働時間管理・勤務態度評価等の指揮命令的運用は禁止（利用規約に明記）
- 偽装請負を防ぐガイドライン（`/legal/job-matching-guideline`）を必須とし、規約同意を求める

### 12.3 下請法

- BtoB 取引（ADMIN ユーザーが法人代表）で **資本金 1,000 万円超の Requester** が **資本金 1,000 万円以下の Worker（法人）** に発注する場合、下請法の対象となる可能性
- 対応:
  - 発注時に発注書面（デジタル）を自動生成（業務内容・報酬・支払期日を明記）
  - 契約成立（MATCHED）で PDF を両者にメール送付（F12.1 PDF 生成機能を利用）
- 個人事業主の Worker は下請法対象外だが、**契約書面整備のため同一フロー** を運用

### 12.4 特定商取引法

- プラットフォーム運営者表示義務: `/legal/tokushoho` に明記（Mannschaft 運営会社名・住所・代表者・連絡先・手数料）
- 求人には Requester 情報の表示義務あり（チーム名・代表者相当・連絡先）
- キャンセル・返金規約の明示（§11.4 を利用規約に転載）

### 12.5 景品表示法

- 手数料は「10% + 100円」「2% + 100円」を明確表記
- 「実質無料」「お得」等の誤認誘発表現禁止
- 料金シミュレーター（`/help/jobs/fee-calculator`）を提供

### 12.6 確定申告・支払調書

- **年間 20 万円超の受取 Worker** に対して、原則として本人確定申告が必要（源泉徴収義務なしの業務委託）
- 支払調書（法定調書）作成義務:
  - 年間 5 万円超を Requester が同一 Worker に支払った場合、Requester が支払調書作成・税務署提出義務
  - Mannschaft は Requester 向けに **年間支払明細 CSV / PDF** を提供（1 月中旬、`/reports/annual-payment` 画面）
  - 支払調書のフォーマットに合わせた出力（氏名・住所・マイナンバーは Requester 側で追記）
- Worker 向けには **年間受取明細 CSV / PDF** を提供（確定申告補助）

### 12.7 マイナンバー

- Mannschaft はマイナンバーを**取得しない**（仲介の立場に留まるため取得根拠がない）
- Requester が支払調書作成時に Worker から直接取得するべき旨を利用規約 / ヘルプに明記
- Worker プロフィールにマイナンバーを保存する UI は提供しない

### 12.8 未成年者

- §4.4 の制限を UI・API でハード実装
- 未成年 Worker の親権者同意は F04.9 確認通知で取得し `job_minor_consents` に記録
- 有効期限 1 年（毎年再同意）
- 15 歳未満は完全禁止（バリデーション）
- 危険作業フラグ（`is_dangerous = TRUE`）の求人は未成年 Worker から非表示

### 12.9 GDPR / 個人情報保護法

- F12.3 GDPR 対応に準拠。ユーザーのデータ削除要求時:
  - Stripe Customer / Connect アカウント情報は Stripe の保持期間に従う（削除は Stripe の別フロー）
  - Mannschaft 側 `stripe_connect_accounts` は `deleted_at` で論理削除 + 個人特定カラムを匿名化
  - 支払記録（`job_payments`）は法人税法で 7 年保持義務あり。削除要求時は明示的に「7年保持」とユーザーに説明
  - **位置情報（`job_check_ins.geolocation_*`）**: 契約完了後 90 日で自動削除、それ以前でも Worker 退会要求時は即時削除。位置情報は「要配慮個人情報」に準じて扱う
  - **内部評価メモ（`job_reviews`）**: Worker 退会時は `reviewee_user_id` を匿名化（別途ダミーユーザー ID に置換）、チーム運営の履歴整合性のために本文は残す
  - **履歴データの Worker 退会時扱い**: `job_contracts` は法人税法保持義務があるため、`worker_user_id` を匿名化し、`worker_display_name_snapshot` は削除（`worker_display_name_snapshot=NULL` + `anonymized_at` を記録）

### 12.10 利用規約・業務委託契約書テンプレート

- プラットフォーム利用規約（一般）
- **業務委託契約テンプレート**（Requester ⇔ Worker 間）: MATCHED 時点で両者同意済みとする（クリックラップ同意）
- 免責条項:
  - Mannschaft は業務委託契約の当事者ではない
  - 業務遂行中の事故・損害について Mannschaft は責任を負わない
  - 紛争調整は提供するが、最終的な法的紛争は当事者間で解決

### 12.11 位置情報取得の同意（個人情報保護法）

- **取得目的の明示**: 「業務場所との照合による不正防止」を利用規約・プライバシーポリシーに明記
- **事前同意**: 初回チェックイン時にブラウザ Geolocation 許諾 + アプリ内同意チェックボックス（二重同意）
- **拒否権の保証**: Geolocation 拒否でもチェックイン自体は成立（§10.10 参照）
- **オプトアウト**: ユーザー設定画面で「今後位置情報を送信しない」スイッチを提供、即座に反映
- **第三者提供なし**: Stripe を含む外部サービスには位置情報を送信しない（Stripe には業務金額・Worker ID のみ送信）
- **本人による参照権**: 自分の `job_check_ins` レコードは API 経由で取得可能（GDPR 15 条・個人情報保護法 28 条準拠）
- **記録媒体**: 個人情報保護委員会ガイドラインに準拠した暗号化（AES-256-GCM）で保存

### 12.12 JOBBER の業務委託独立性（第三版新規）

- **結論**: JOBBER は労働派遣法の派遣労働者ではなく、Requester と直接業務委託契約を結ぶ**独立した業務委託先**として扱う
- **理由・根拠**:
  - Mannschaft は招待 / マッチング / 決済媒介を提供するのみで、JOBBER に対する指揮命令権を持たない
  - Requester と JOBBER の間で個別契約が成立し、Mannschaft は労働者派遣事業者ではない
  - JOBBER は複数チームに同時登録できる（専属性なし、独立事業者性を担保）
  - 業務遂行時間・方法の裁量は JOBBER 側にある（成果物ベースの業務委託）
- **規約条項**: `/legal/jobber-agreement` に以下を明記
  - JOBBER は Requester と直接業務委託契約を結ぶ独立した事業者である
  - Mannschaft は契約の当事者ではない
  - 労働保険・労災の対象ではない（民間保険の任意加入は推奨）
  - 報酬は業務委託料であり、給与ではない
- **偽装請負への注意**: Requester 側の UI に「JOBBER への業務指示は成果物ベースでお願いします。勤務時間管理・業務中の細かい指示は偽装請負リスクがあります」と注意喚起
- **労働時間管理の禁止**: 運営確定方式での「業務時間入力」は**実労働時間の管理**ではなく、**報酬計算のための業務遂行時間の事後確認**として位置付ける（規約・UI で明記）

### 12.13 7 日間エスクローの資金保有法的整理（第三版新規）

- **結論**: エスクロー 7 日間はあくまで **Stripe の authorization hold** であり、Mannschaft は資金を保有しない
- **法的論拠**:
  - Stripe の `capture_method=manual` における authorized_amount は、card-issuer が hold するだけで、Stripe / Mannschaft の銀行口座には入金されていない
  - Mannschaft は hold 解除（capture）の指示を行うのみ
  - 資金決済法上の資金移動業に該当しない（§12.1 の既存整理を維持）
- **消費者表示**: ヘルプページ・エスクロー画面で「お客様のカードは **オーソリ（承認）** のみで、引落しはまだ発生していません」と明示
- **早期 release / 自動 capture のタイミング**: capture 実行時点で Requester のカードから初めて引落しが発生し、同時に Worker の Stripe Express アカウントへ transfer される（Mannschaft は中間保有なし）
- **DISPUTED_CAPTURED 状態での資金保有**:
  - 6 日 22 時間時点で強制 capture を実行した場合、資金は Stripe platform balance に一時的に滞留
  - しかしこの滞留は **Stripe 残高（Stripe Payments Japan 株式会社の資産）** であり、Mannschaft の銀行口座には未入金
  - Stripe が資金移動業者として規制を受けているため、Mannschaft 側で別途資金移動業の登録は不要
- **監査・会計**: DISPUTED_CAPTURED 期間の Stripe 残高は「仮受金（短期負債）」として F08.6 予算会計で管理

### 12.14 Jobber 総合掲示板の特商法対応（第三版新規）

- 総合掲示板経由で応募する場合も、Requester 情報（チーム名・運営責任者・連絡先）が `/legal/team/{teamId}` で閲覧可能であることを必須化
- 求人に自動的に運営責任者の表記リンクを付与
- 応募ボタン横に「特商法表示を確認する」リンクを常設

---

## 13. エラー処理・障害対応

### 13.1 決済失敗時の再試行ポリシー

- カード決済失敗（`payment_intent.payment_failed`）:
  - Requester へ通知
  - 24 時間以内に別カードで再試行可能な UI 提供
  - 24 時間超で未解決なら契約を `CANCELLED`、Worker へ通知・謝罪

### 13.2 Stripe API 障害

- Resilience4j Circuit Breaker を PaymentIntent 作成／capture 経路に適用
- Open 時（連続 5 失敗）は 60 秒遮断し、ユーザーに「決済システムが一時的に不安定です。数分後に再試行下さい」と表示
- 監視: Prometheus でエラーレート・レイテンシを収集、PagerDuty アラート（P1）

### 13.3 Worker 銀行口座が無効

- `payout.failed` 受信 → Worker へ通知、`stripe_connect_accounts.status = 'RESTRICTED'`
- 次回採用確定前に口座再登録を強制
- 既に透過済みの報酬は Stripe の `retry_payout` 設定に従う

### 13.4 Requester 返金要求

- §11.4 のポリシーに従う
- 契約状態別の返金可否・金額を自動計算
- 承認後 `PaymentIntent.cancel()` または `refunds.create()` を実行

### 13.5 整合性破壊の検知

- 日次バッチで `job_payments.amount_jpy` と `job_contracts.requester_total_payment_jpy` の不一致を検出
- Stripe Reports API との週次リコンシリエーション
- 検出時は Slack 通知 + SYSTEM_ADMIN 画面にアラート

---

## 14. 保守性観点

### 14.1 Stripe API バージョン管理

- §8.7 参照
- 3 ヶ月ごとの「Stripe changelog ウォッチ」作業を運用手順書に記載
- アップグレード時のチェックリスト（テスト Webhook / Idempotency / 料金計算）

### 14.2 手数料率変更の影響範囲

- 手数料定数は `application.yml` に記述:
  ```yaml
  mannschaft:
    fee:
      requester-percent: 10
      requester-fixed-jpy: 100
      worker-percent: 2
      worker-fixed-jpy: 100
      tax-enabled: true
      tax-rate-percent: 10
      min-reward-jpy: 500
      max-reward-jpy: 1000000
  ```
- `JobFeeCalculator.java`（Domain Service、単一責任）が唯一の計算入口
- 既存契約の手数料スナップショット（`job_contracts.*_fee_jpy` 列）は変更しない（過去契約は凍結）
- 変更は ADMIN ダッシュボードからではなく、デプロイ（環境変数変更）で行う（監査性確保）

### 14.3 監査ログ完全性

- §10.9 参照。すべての金銭移動を `audit_logs` + `job_payments` + Stripe Event の 3 箇所で冗長記録
- 日次で 3 者の整合性をバッチ検査

### 14.4 データ保持期間

- `job_payments` / `audit_logs`: **7 年**（法人税法第 150 条に基づく帳簿保存義務）
- `job_postings` / `job_applications` / `job_contracts`: **5 年**（民法上の債権消滅時効）
- `job_dispute_cases`: **10 年**（重要案件、参照時間が長期）
- `chat_rooms` 関連（契約紐付け）: 契約完了後 **90 日**、以降アプリから非表示だが DB 保持 5 年（分析・紛争再開用）
- `job_check_ins`: **契約と同じ 5 年保持**。ただし `geolocation_*` 列は契約完了後 **90 日で自動削除**（`GeolocationPurgeJob`）
- `job_qr_tokens`: `expires_at + 24 時間` で物理削除（`JobQrTokenCleanupJob`、毎時実行）。`used_at IS NOT NULL` レコードは 7 日保持してから削除
- `job_reviews`: **契約と同じ 5 年保持**（内部記録は次回起用判断のために中長期保持）
- **`jobber_profiles`**（第三版新規）: Worker 退会時は即時削除（`ON DELETE CASCADE`）、在籍中は無期限保持
- **`jobber_team_invitations`**（第三版新規）: `EXPIRED / DECLINED / REVOKED` は **30 日保持**（監査のため）、`PENDING` は `expires_at + 7 日` で物理削除、`ACCEPTED` は監査目的で **1 年保持**（`JobberInvitationExpiryJob`、毎日実行）
- **`job_time_confirmations`**（第三版新規）: **契約と同じ 5 年保持**（労働時間の根拠として中長期保持、税務対応）

### 14.5 運用ダッシュボード

- SYSTEM_ADMIN 専用画面:
  - 日次取引件数・金額
  - プラットフォーム粗利
  - Stripe 手数料総額
  - 紛争件数・解決 SLA
  - Connect オンボーディング完了率

---

