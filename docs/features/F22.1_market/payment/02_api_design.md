# F22.1 市（Market）統一決済 — 02. API設計（謝礼＋会費・共通基盤）

> 親: [README.md](README.md) ／ 関連: [01_data_model.md](01_data_model.md) / [03_security.md](03_security.md)

---

## 0. 共通送金サービス `ConnectChargeService`（統一基盤・正典）

謝礼（`RECRUITMENT`・エスクローモード）と会費（`MEMBERSHIP`・即時モード）を **1つの共通サービス `ConnectChargeService` に集約**する（README §1.0）。Controller/イベントリスナはモードに依存せず本サービスを呼び、サービスが `EscrowSourceKind` ＋ `capture_mode` で Stripe API を分岐する。

```java
// payment.escrow.ConnectChargeService（新規・P2-b）
// 共通の課金生成（モードで capture_method を分岐・手数料折半を一元計算）
EscrowChargeResult createCharge(ConnectChargeCommand cmd);
//   cmd: sourceKind(RECRUITMENT|MEMBERSHIP), captureMode(MANUAL|AUTOMATIC),
//        faceAmount(額面), payerCustomerId, payeeConnectAccountId, idempotencyKey, ...
// エスクローモード（謝礼）: 最終認証時の確定
void captureCharge(UUID escrowId, String idempotencyKey);          // capture+transfer
// 共通: 返金（受取側 ADMIN 操作・reverse_transfer:true / refund_application_fee:false）
String refundCharge(UUID escrowId, long faceRefundAmount, String reason, String idempotencyKey);
// 共通: 与信取消（capture 前のみ・即時モードは対象外）
void cancelAuthorization(UUID escrowId, String idempotencyKey);
```

- **即時モード（会費・MEMBERSHIP）**: `captureMode=AUTOMATIC` → `createDestinationPaymentIntent(capture_method='automatic')` → INSERT 時 `status=CAPTURED`・`hold_expires_at=NULL`・即 transfer。与信フェーズなし。
- **エスクローモード（謝礼・RECRUITMENT）**: `captureMode=MANUAL` → 与信（`AUTHORIZED`/`HELD`）→ 最終認証で `captureCharge()`。
- **手数料折半（5%＝支払者2.5%+受取側2.5%）の計算は本サービス内 `PaymentFeeCalculator` に一元化**（§3.5・散在禁止）。

---

## 1. エンドポイント一覧

| # | メソッド/パス | 用途 | モード | 認可 |
|---|---|---|---|---|
| 1 | `POST /api/v1/payment/connect/onboarding-link` | Connect onboarding リンク発行（個人/チーム/組織） | 共通 | 個人=本人・TEAM/ORG=scope ADMIN |
| 2 | `GET /api/v1/payment/connect/status` | 自分（または指定scope）の Connect 状態 | 共通 | 個人=本人・TEAM/ORG=scope ADMIN |
| 3 | `PUT /api/v1/teams/{teamId}/recruitment-listings/{id}/payment` ほか | 札の謝礼設定（額面・受領主体） | 謝礼 | 札主 scope ADMIN（既存札API拡張） |
| 4 | （内部）応募成立時の与信 | `incrementConfirmed()` フック | 謝礼（エスクロー） | イベント駆動・外部API無し |
| 5 | （内部）最終認証時 capture+transfer | `MarketFinalize` confirm フック | 謝礼（エスクロー） | イベント駆動・外部API無し |
| 5b | （会費・P2-e）会員の会費支払い（即時） | F08.2 既存会費 API が `ConnectChargeService.createCharge(MEMBERSHIP, AUTOMATIC)` を呼ぶ | 会費（即時） | 会員本人（F08.2 認可に委譲） |
| 6 | `POST /api/v1/payment/escrow/{id}/refund` | 返金（部分/全額・`reverse_transfer:true`/`refund_application_fee:false`） | 共通 | **受取側 scope の ADMIN**（運営非関与・設定A） |
| 7 | `POST /api/v1/webhooks/stripe/connect` | Connect Webhook 受け口 | 共通 | permitAll（署名検証） |
| 8 | `GET /api/v1/payment/escrow/{id}` | エスクロー取引の状態照会 | 共通 | 受取側 scope ADMIN・受領者本人 |

> パス接頭辞は既存 payment ドメイン（`/api/v1/payment/...`・`/api/v1/webhooks/stripe...`）に揃える。札の謝礼設定は F03.11 既存の Team/Organization 別 Controller に**拡張**で乗せる（市から直接立てない原則）。会費（#5b）は F08.2 既存の会費支払い API が内部で `ConnectChargeService` を呼ぶ形に P2-e で置換する（新規エンドポイントを増やさない）。

---

## 2. Connect onboarding

### 2.1 `POST /api/v1/payment/connect/onboarding-link`

> ⚠️ **実装注意 — Connect onboarding パスの統一確認**
> 既存の認可ベースライン `docs/security/01_authorization_baseline.md` には `/api/v1/users/me/stripe-connect`（`.authenticated()`）が**既出**として定義されている可能性がある。本設計が採用するパス `/api/v1/payment/connect/onboarding-link` はパス系統が異なるため、実装着手前に以下を確認・統一すること:
> 1. `SecurityConfig`（または `HttpSecurityConfig`）で `/api/v1/users/me/stripe-connect` が `requestMatchers` に含まれているか確認する。
> 2. 旧パスを廃止するか、本設計パスへリダイレクトするかを決定し、`01_authorization_baseline.md` を更新する。
> 3. FE が旧パスをハードコードしていないか全文検索（`stripe-connect` / `stripeConnect`）で確認する。
> 両パスが並立したままになると SecurityConfig のパーミッション設定が分裂し、認可抜け漏れの原因となる。

受領者になる主体（個人/チーム/組織）の Stripe Express アカウントを作成し、hosted onboarding への遷移リンクを返す。

**Request**
```json
{
  "scopeKind": "USER | TEAM | ORG",
  "scopeId": 123,                       // TEAM/ORG 時必須（teamId/orgId）。USER 時は無視（本人固定）
  "returnUrl": "https://app/.../connect/return",
  "refreshUrl": "https://app/.../connect/refresh"
}
```

**処理**
```
1. 認可: scopeKind=USER → 本人のみ / TEAM → AccessControlService.checkPermission(teamId, ADMIN相当)
                          / ORG → checkAdminOrHasPermission(orgId, ...)  ※ TEAM/ORG で API を取り違えない（F22.1 04 §1.1 と同轍）
2. connect_accounts を (scope_kind, scope_id) で検索（deleted_at IS NULL）
   ├─ 無し: StripePaymentProvider.createConnectAccount(country=JP, capabilities={transfers})
   │        → connect_accounts INSERT (onboarding_status=ONBOARDING)
   └─ 有り: 既存 acct を流用（onboarding_status を ONBOARDING に戻す）
3. StripePaymentProvider.createAccountLink(acct_xxx, type='account_onboarding', returnUrl, refreshUrl)
4. onboardingUrl を返す（Stripe hosted へ FE がリダイレクト）
```

**Response 200**
```json
{
  "connectAccountId": "018f...uuid",
  "stripeAccountId": "acct_xxx",
  "onboardingStatus": "ONBOARDING",
  "onboardingUrl": "https://connect.stripe.com/setup/...",
  "expiresAt": "2026-06-02T12:34:56Z"
}
```

### 2.2 `GET /api/v1/payment/connect/status?scopeKind=&scopeId=`

```json
{
  "connectAccountId": "018f...uuid",
  "scopeKind": "TEAM",
  "scopeId": 123,
  "onboardingStatus": "READY | PENDING | ONBOARDING | RESTRICTED | DISABLED",
  "chargesEnabled": true,
  "payoutsEnabled": true,
  "requirementsDue": ["individual.verification.document"]   // RESTRICTED 時のみ
}
```
> `account.updated` Webhook（§4）が `onboarding_status`/`payouts_enabled` を更新する。FE は本 API でポーリングするか、return_url 着地後に再取得する。

---

## 3. 札の謝礼設定（既存札APIの拡張）

市の札立ては**チーム/組織ダッシュボードからのみ**（F22.1 本体 §2）。謝礼設定は既存の札作成/更新 API のリクエストへ**フィールド追加**で乗せる（新規エンドポイントを増やさない）。

**Request 追加フィールド（札作成/更新）**
```json
{
  "paymentEnabled": true,
  "price": 5000,                         // JPY 整数（既存 price 列）
  "payeeKind": "USER | TEAM | ORG",      // 受領主体（札ごと選択）
  "payeeUserId": 456                     // payeeKind=USER 時必須（審判/助っ人個人）
}
```

**検証（Service・エラーコード）**
| 条件 | エラー |
|---|---|
| `paymentEnabled=true && price==null` | `PAYMENT_010 PRICE_REQUIRED`（既存検証を拡張） |
| `paymentEnabled=true && payeeKind==null` | `PAYMENT_011 PAYEE_REQUIRED` |
| `payeeKind=USER && payeeUserId==null` | `PAYMENT_012 PAYEE_USER_REQUIRED` |
| 受領主体の `connect_accounts` が READY/payouts_enabled でない | **エラーにせず受理可**（応募成立時に `HELD`・§5）。ただし札立てフォームで警告表示（04 §2） |
| `payeeKind=USER` の対象が札主チームの所属でない | `PAYMENT_013 PAYEE_NOT_IN_SCOPE`（個人受領者は札主に紐づく者に限定・IDOR 防止） |
| `payeeKind=TEAM/ORG` で受領主体が札主 scope と不一致 | `PAYMENT_013` |

> 受領者の onboarding が未完了でも**札は立てられる**（応募者を集めながら口座登録を進められる）。実際の払出時点で payouts_enabled を再判定する（§5）。
> 札立てフォームの謝礼額入力は**額面（`face_amount`）**である。支払者の請求額（額面+2.5%）と受取側送金額（額面−2.5%）は §3.5 の計算で導出され、UI に明示する（04 §2/§3）。

---

## 3.5 手数料折半の計算規約（5% ＝ 支払者2.5% + 受取側2.5%・マスター確定: 案あ）

謝礼・会費の双方に**共通**で適用する（`PaymentFeeCalculator` に一元化・散在禁止）。額面（`face_amount`）を入力とし、円ゼロデシマル・**四捨五入**で計算する。

```
face_amount            … 受取側が設定した額面（謝礼額/会費額）
payer_surcharge        = round(face_amount × 0.025)        // 支払者上乗せ（2.5%）
amount（課金額）        = face_amount + payer_surcharge       // Stripe へ渡す請求額
application_fee_amount  = round(face_amount × 0.05)          // 総手数料（5%）= Mannschaft が徴収
payee_transfer_amount  = amount − application_fee_amount     // 受取側送金額（≈ 額面−2.5%）
stripe_fee（参考）      = round(amount × stripe_fee_rate)     // stripe_fee_rate 既定 0.036（設定値）
mannschaft_net（参考）  = application_fee_amount − stripe_fee  // Mannschaft 純益 ≈ 額面の 1.31%
```

### 3.5.1 具体例（額面 10,000 円・JPY）

| 項目 | 金額 | 計算 | 記録先 |
|---|---|---|---|
| 額面（`face_amount`） | **10,000 円** | 受取側設定 | `escrow_transactions.face_amount` |
| 支払手数料（2.5%上乗せ） | **+250 円** | `round(10,000 × 0.025)` | （`amount` に内包） |
| **課金額（`amount`）** | **10,250 円** | 額面 + 支払手数料 | `escrow_transactions.amount`（Stripe 課金額） |
| **総手数料（`application_fee_amount`）** | **500 円** | `round(10,000 × 0.05)` | `escrow_transactions.application_fee_amount` |
| 受取側送金額 | **9,750 円** | 10,250 − 500 | `ledger_entries`(TRANSFER_OUT) |
| Stripe 実手数料（≈3.6%・課金額基準） | **≈369 円** | `round(10,250 × 0.036)` | `ledger_entries`(FEE)・**Stripe Webhook の実額で記録** |
| **Mannschaft 純益** | **≈131 円**（額面の ≈1.31%） | 500 − 369 | 日次照合で可視化（§6.3） |

> - 受取側視点: 「額面 10,000 円 → 受取 9,750 円（−2.5%）」。支払者視点: 「額面 10,000 円 → 請求 10,250 円（+2.5%）」。
> - **Stripe 実手数料はグロスアップ後の課金額（10,250 円）にかかる**ため、当初想定 1.4% より純益がわずかに低い（≈1.31%）。**マスター承認＝この純益で OK（案あ）**。
> - `ledger_entries`(FEE) には**設定値の概算ではなく Stripe Webhook（`balance_transaction`）の実手数料**を記録し、日次照合で純益の微変動を可視化する（症状を隠さない・README §3.4 / §6.3）。
> - `chk_et_fee: application_fee_amount(500) ≤ amount(10,250)` を充足。

---

## 4. Connect Webhook（`POST /api/v1/webhooks/stripe/connect`）

### 4.1 受け口と冪等性
- **permitAll**（`Stripe-Signature` 検証で守る）。既存 `StripeWebhookController` に Connect 用ハンドラを追加するか、別 `@PostMapping("/connect")` を切る。
- **署名検証**: `StripePaymentProvider.constructEvent(payload, sigHeader)`（既存・Connect 用署名シークレットは別環境変数 `STRIPE_CONNECT_WEBHOOK_SECRET`）。
- **冪等性ゲート**: 受信直後に `stripe_webhook_events` へ `INSERT event_id`。一意制約違反（重複受信）なら**既処理として 200 で即返す**（再処理しない）。

```
受信 → 署名検証(失敗=400) → INSERT stripe_webhook_events(event_id)
  ├─ 一意制約違反 → 既処理 → 200（no-op・冪等）
  └─ 新規 → ハンドラ実行 → process_status=PROCESSED → 200
```

### 4.2 ハンドリングするイベント

| イベント | エンドポイント | 処理 |
|---|---|---|
| `account.updated` | connect | `connect_accounts` の `onboarding_status`/`charges_enabled`/`payouts_enabled`/`requirements_due` を反映。payouts_enabled=true へ遷移時、`HELD` の escrow があれば §5 で capture 再開トリガ |
| `account.application.deauthorized` | connect | `onboarding_status=DISABLED`。以降の払出を停止し札主へ通知 |
| `capability.updated` | connect | capabilities 状態の鏡像更新 |
| `payment_intent.succeeded` | platform（既存に追加） | `escrow_transactions.status=CAPTURED`・`captured_at` 記録・`ledger_entries`（CAPTURE/TRANSFER_OUT/FEE）追記 |
| `payment_intent.amount_capturable_updated` | platform | 与信額確定の監査ログ（`AUTHORIZED` 確認） |
| `payment_intent.canceled` | platform | `status=CANCELLED`（hold 失効/取消） |
| `charge.refunded` | platform | `refunds.status=SUCCEEDED`・escrow を `REFUNDED`/`PARTIALLY_REFUNDED` に。`ledger_entries`(REFUND) 追記 |
| `transfer.created` | platform | `ledger_entries.stripe_object_id` に `tr_xxx` を補記（突合） |

---

## 5. 与信 → 払出（内部フロー・イベント駆動）

外部 API ではなく、recruitment ドメインのイベントを `payment.escrow` が購読する（README §7・疎結合）。

### 5.1 与信（謝礼・エスクローモード・応募成立 OPEN→FULL）
```
RecruitmentListingEntity.incrementConfirmed() → RecruitmentConfirmedEvent 発火
  ↓ (payment.escrow リスナ → ConnectChargeService.createCharge)
1. 札が payment_enabled か判定（FALSE なら何もしない）
2. 受領主体の connect_accounts を解決（payee_kind/受領主体ID）
3. payer の Stripe Customer 解決（既存 stripe_customers 再利用 or 新規 createCustomer）
4. PaymentFeeCalculator で折半計算（face_amount=price → amount=額面+2.5%, application_fee=額面×5%・§3.5）
5. StripePaymentProvider.createDestinationPaymentIntent(
     amount=（額面+2.5%）, currency='jpy', capture_method='manual',   // エスクローモード
     application_fee_amount=（額面×5%）, transfer_data.destination=acct_xxx, on_behalf_of=acct_xxx,
     idempotency_key="escrow-{listingId}-{participantId}")
6. escrow_transactions INSERT
     (source_kind=RECRUITMENT, capture_mode=MANUAL, face_amount, amount, application_fee_amount,
      status=AUTHORIZED, authorized_at, hold_expires_at=now+最大7日)
   ├─ connect_accounts.payouts_enabled=false → status=HELD（capture 待ち）
   └─ ledger_entries(AUTHORIZE) 追記
```
> 支払者のカード入力は**応募ダイアログで Stripe Checkout/Elements を用いブラウザから直送**（カード番号は自社を通らない・PCI SAQ-A・03 §1）。FE は client_secret を受け取り confirm する。決済確認画面には**手数料内訳（額面/支払手数料2.5%/合計）と「決済手数料は返金されません」**を明示する（04 §3.1）。

### 5.1b 会費（即時モード・MEMBERSHIP・P2-e）
```
F08.2 会費支払い API → ConnectChargeService.createCharge(sourceKind=MEMBERSHIP, captureMode=AUTOMATIC, faceAmount=会費額)
1. 受取側 connect_accounts を解決（チーム/組織の Connect・payee_kind=TEAM/ORG）
2. PaymentFeeCalculator で折半計算（§3.5・謝礼と共通）
3. StripePaymentProvider.createDestinationPaymentIntent(
     amount=（額面+2.5%）, currency='jpy', capture_method='automatic',  // 即時モード＝即 capture
     application_fee_amount=（額面×5%）, transfer_data.destination=acct_xxx, on_behalf_of=acct_xxx,
     idempotency_key="membership-{memberPaymentId}")
4. escrow_transactions INSERT (source_kind=MEMBERSHIP, capture_mode=AUTOMATIC,
     status=CAPTURED, captured_at=now, hold_expires_at=NULL)   // 与信フェーズなし・即 transfer
5. ledger_entries(CAPTURE/TRANSFER_OUT/FEE) 追記
```
> 会費は与信を経ず即時確定（CAPTURED）。受取側（チーム/組織）の Connect 口座へ直接入金され、Mannschaft は資金を保持しない。手数料折半・台帳・送金経路は謝礼と完全に共通（README §1.0.1）。

### 5.2 払出保留（HELD）と 72h 猶予
- 受領者 onboarding 未完了（`payouts_enabled=false`）で与信が立った場合、`status=HELD`。
- `account.updated` で payouts_enabled=true になったら、HELD の escrow を AUTHORIZED 相当として capture 可能化（最終認証待ちへ）。
- **72h 猶予**（F13.1 §8.9 自動キャンセル相当を市に適用）: HELD のまま onboarding 完了しないまま `hold_expires_at` 接近 or 72h 超過した場合、与信を取消（`PaymentIntent.cancel()` → `CANCELLED`）し、**応募者へ「受領者の口座未登録のため謝礼を確定できず取消」通知**＋札主へ催促通知。症状を隠さず原因（onboarding 未完了）を通知に明記（CLAUDE.md 根治原則）。

### 5.3 払出（最終認証 FULL→COMPLETED）
```
MarketFinalizeService / MarketFinalizeConfirmedListener:
  confirm トランザクション内（recruitment_listings 札行を PESSIMISTIC_WRITE で取得）
  → MarketFinalizedEvent 発火
    ↓ (payment.escrow リスナ・同一 confirm の後続処理として札行ロック直下)
1. escrow_transactions を source_id で取得（status=AUTHORIZED のもの）
2. payouts_enabled 再判定（false なら HELD のまま・§5.2 へ）
3. StripePaymentProvider.captureManualPaymentIntent(pi_xxx, idempotency_key="capture-{escrowId}")
   → capture と同時に transfer_data.destination へ送金（application_fee_amount 控除）
4. status=CAPTURED, captured_at 記録 / ledger_entries(CAPTURE/TRANSFER_OUT/FEE) 追記
```
> **二重払出防止**: 札行 `PESSIMISTIC_WRITE` ロック（F22.1 本体 04 §2 と同戦略）の直下で capture を呼ぶため、並行 confirm が直列化される。さらに Stripe へ `idempotency_key="capture-{escrowId}"` を渡し、ネットワーク再送でも二重 capture を Stripe 側でも拒否（二重防御）。

### 5.4 自動 capture バッチ（hold 失効回避）
- `@Scheduled` バッチが `status IN (AUTHORIZED, HELD, DISPUTED) AND hold_expires_at <= now()+2h` を `FOR UPDATE SKIP LOCKED` で取得し capture（F13.1 §8.9.5 踏襲）。
- `DISPUTED`（最終認証未了で hold 接近）は**先 capture 後返金**戦略: 一旦 capture して資金を確保し、後で仲裁結果に応じ全額/部分返金（F13.1 §8.9.3）。

---

## 6. 返金 / 与信取消（受取側 ADMIN 操作・設定A・マスター確定）

### 6.1 `POST /api/v1/payment/escrow/{id}/refund`

> **操作主体＝チーム/組織の管理者（受取側 scope の ADMIN）。Mannschaft 運営は関与しない。** 無関係 scope は 404 秘匿（03 §3/§4）。

```json
// Request（amount は額面ベースの返金額。0 < amount ≤ face_amount）
{ "amount": 5000, "reason": "cancellation", "reasonDetail": "天候中止のため" }
```
**処理**
```
1. 認可: 受取側 scope の ADMIN のみ（IDOR: escrow.payee の scope 所有権検証・03 §4）
2. status 分岐:
   ├─ AUTHORIZED/HELD（capture 前）: PaymentIntent.cancel() → CANCELLED
   │     （返金でなく与信取消・支払者に課金なし・即時モード MEMBERSHIP は capture 前が無いため対象外）
   └─ CAPTURED（capture 後）: Refund.create(
   │       amount,
   │       reverse_transfer = true,          // 返金額を受取側 Connect 残高から戻す（Mannschaft 負担ゼロ）
   │       refund_application_fee = false,    // 徴収済み Mannschaft 手数料は返金しない（設定A）
   │       idempotency_key="refund-{escrowId}-{seq}")
   │     → refunds INSERT (status=PENDING) → charge.refunded Webhook で SUCCEEDED 確定
   │     → amount==face_amount なら REFUNDED / 部分なら PARTIALLY_REFUNDED
3. ledger_entries(REFUND or CANCEL) を **監査追記のみ**（自前の逆仕訳ロジックは作らない＝金を動かすのは Stripe）
```

> - **金を動かすのは Stripe**: `reverse_transfer:true` で受取側 Connect 残高から返金原資を戻すため、Mannschaft の自前逆仕訳・自社負担は発生しない。`refunds` テーブルは記録専用。
> - **Stripe 決済手数料（≈3.6%）は返金されない**（Stripe 仕様・`refund_application_fee:false` とは別に Stripe 手数料そのものが戻らない）。利用規約・決済画面で事前周知（README §3.5.1 / 03 §10 / 04 §3.1）。
> - **受取側残高不足**: 受取側 Connect 残高が返金額に満たない場合、Stripe がマイナス残高を後続入金/口座引落で**自動回収**する。**Mannschaft には請求が来ない**（03 §6 運用注意）。

**エラー**
| 条件 | エラー |
|---|---|
| 既に REFUNDED | `PAYMENT_020 ALREADY_REFUNDED` |
| `amount > 残額（face_amount − 既返金額）` | `PAYMENT_021 REFUND_AMOUNT_EXCEEDS` |
| 受取側 scope ADMIN でない | `PAYMENT_001 FORBIDDEN`（403・無関係 scope は 404） |

### 6.2 札下げ・期限切れ連携（自動）
`cancelByAdmin()` / `autoCancel()` が `RecruitmentCancelledEvent` を発火 → `payment.escrow` が与信中（AUTHORIZED/HELD）の escrow を `PaymentIntent.cancel()` で取消（支払者課金なし）。capture 済なら全額 Refund（`reverse_transfer:true`/`refund_application_fee:false`・札下げ＝役務不履行のため）。

### 6.3 リコンシリエーション（整合バッチ・純益可視化）
- 15 分間隔で `PaymentIntent.retrieve`／日次で `balance_transaction` を取得し、`ledger_entries` と Stripe balance を突合（F13.1 §8.8 踏襲）。
- 1 取引の借方合計＝貸方合計の検算（01 §3.3）。不一致は**握りつぶさずアラート**（CLAUDE.md 根治原則）。
- **純益の微変動可視化**: `ledger_entries`(FEE) に **Stripe Webhook（`balance_transaction.fee`）の実手数料**を記録し、`application_fee_amount`（5%徴収）− Stripe 実手数料＝Mannschaft 純益（≈額面の1.31%）を日次集計。グロスアップ後課金額に対する Stripe 手数料の実額が想定（0.036）からブレた場合も台帳で可視化し、症状を隠さない（README §3.4）。

---

## 7. エラーコード一覧（`PAYMENT_xxx`）

| コード | HTTP | 意味 |
|---|---|---|
| `PAYMENT_001` | 403 | 認可エラー（札主/受領者本人でない・IDOR） |
| `PAYMENT_002` | 404 | escrow/connect_account が存在しない（または scope 不一致で秘匿） |
| `PAYMENT_010` | 422 | `PRICE_REQUIRED`（payment_enabled なのに price なし） |
| `PAYMENT_011` | 422 | `PAYEE_REQUIRED`（payeeKind なし） |
| `PAYMENT_012` | 422 | `PAYEE_USER_REQUIRED`（payeeKind=USER で payeeUserId なし） |
| `PAYMENT_013` | 422 | `PAYEE_NOT_IN_SCOPE`（受領者が札主 scope に紐づかない） |
| `PAYMENT_020` | 409 | `ALREADY_REFUNDED` |
| `PAYMENT_021` | 422 | `REFUND_AMOUNT_EXCEEDS` |
| `PAYMENT_030` | 409 | `ONBOARDING_NOT_READY`（払出時に payouts 不可・HELD 化で通常はエラーにしないが手動操作時の保険） |
| `PAYMENT_040` | 400 | Webhook 署名検証失敗 |
| `PAYMENT_041` | 409 | 与信失敗（Stripe 側エラー・カード拒否）。応募成立をロールバックし応募者へ通知 |

> **実装注記（Connect 系コードの命名・P2-a 以降）**: 上表の `PAYMENT_011/013/040` 等の番号は<b>概念対応の設計記載</b>であり、実コードのエラーコード文字列とは一致しない。既存 `PaymentErrorCode`（`PAYMENT_001`〜`PAYMENT_027`）との<b>文字列衝突を回避</b>するため、Connect 系は別 enum `ConnectPaymentErrorCode` を新設し `PAYMENT_C0xx` 系（例: 署名検証失敗 = `PAYMENT_C040`）を採用した。後続フェーズ（P2-b 与信/P2-c 払出）も齟齬防止のため `ConnectPaymentErrorCode`（`PAYMENT_C0xx` 系）を継続使用すること。

---

## 8. `StripePaymentProvider` 追加メソッド（既存を破壊しない）

既存インターフェース（Product/Price/Customer/Checkout/全額Refund/Webhook）は温存し、Connect 系を**追加**する。

```java
// 追加メソッド（payment/stripe/StripePaymentProvider.java へ）
String createConnectAccount(String country, ScopeKind scopeKind, Long scopeId);   // acct_xxx
String createAccountLink(String stripeAccountId, String returnUrl, String refreshUrl); // onboarding URL

// Destination Charge（capture_method を引数化し即時/エスクロー両モードを賄う）
PaymentIntentInfo createDestinationPaymentIntent(
    long amountMinor, String currency, String payerCustomerId,
    long applicationFeeMinor, String destinationAccountId,
    CaptureMethod captureMethod,        // MANUAL（謝礼）/ AUTOMATIC（会費）
    String idempotencyKey);

void captureManualPaymentIntent(String paymentIntentId, String idempotencyKey);   // capture+transfer（エスクローモードのみ）
void cancelAuthorization(String paymentIntentId, String idempotencyKey);          // 与信取消
// 返金（reverse_transfer/refund_application_fee を明示・設定A）
String createPartialRefund(String paymentIntentId, long amountMinor, String reason,
    boolean reverseTransfer, boolean refundApplicationFee, String idempotencyKey);

ConnectAccountInfo retrieveConnectAccount(String stripeAccountId);                 // status 同期用

record PaymentIntentInfo(String paymentIntentId, String clientSecret, String status) {}
record ConnectAccountInfo(boolean chargesEnabled, boolean payoutsEnabled, java.util.List<String> requirementsDue) {}
```
> `clientSecret` は FE が Stripe.js で confirm する際に必要（カード直送・PCI SAQ-A）。

---

## 9. 冪等性キーの規約

| 操作 | idempotency_key |
|---|---|
| 与信作成（謝礼） | `escrow-{listingId}-{participantId}` |
| 会費課金（即時） | `membership-{memberPaymentId}` |
| capture | `capture-{escrowId}` |
| 与信取消 | `cancel-{escrowId}` |
| 返金 | `refund-{escrowId}-{seq}`（部分返金の連番） |
| Webhook 処理 | `stripe_webhook_events.event_id`（DB 一意制約・§4.1） |

> Stripe の idempotency_key（24h 有効）＋ DB の `stripe_webhook_events.event_id` UNIQUE ＋ 札行 `PESSIMISTIC_WRITE` の三重で二重決済を防ぐ。

---

## 10. テスト方針（契約テスト・test-first 先行）

- **認可**: onboarding（個人=本人以外403/チーム=非ADMIN403）、**返金（受取側 scope ADMIN 以外403・無関係 scope 404）**、escrow 照会（無関係 scope → 404 秘匿）。
- **冪等**: 同一 event_id 二重 Webhook → 1 回だけ処理。同一 capture idempotency_key 二重 → 1 回だけ capture。
- **手数料折半（§3.5）**: 額面10,000 → amount=10,250 / application_fee=500 / 受取側送金=9,750。端数 99 円/1 円等で四捨五入が `round(face×0.025)`/`round(face×0.05)` に一致。`application_fee_amount ≤ amount` 不変。
- **2モード**: エスクロー（RECRUITMENT・MANUAL）は AUTHORIZED 経由、即時（MEMBERSHIP・AUTOMATIC）は INSERT 時 CAPTURED・hold_expires_at NULL。`ConnectChargeService.createCharge` がモードで `capture_method` を分岐。
- **状態遷移**: AUTHORIZED→CAPTURED、AUTHORIZED→CANCELLED、HELD→（onboarding完了）→CAPTURED、CAPTURED→PARTIALLY_REFUNDED/REFUNDED、DISPUTED→先capture、（即時）→CAPTURED。
- **返金（設定A）**: capture 済 Refund に `reverse_transfer=true`/`refund_application_fee=false` が渡る。部分返金で残額管理（face_amount 基準）。capture 前は cancel（課金なし）。
- **保留**: payouts_enabled=false で与信 → HELD。72h 超過 → CANCELLED ＋ 通知。
- **JPY**: amount/application_fee=円整数で Stripe へ渡る（ゼロデシマル）。
- **二重払出防止**: 並行 confirm（札行ロック直列化）で capture が 1 回のみ。
- **台帳**: 各 escrow の借方=貸方検算。FEE は Stripe 実手数料で記録（純益可視化・§6.3）。
