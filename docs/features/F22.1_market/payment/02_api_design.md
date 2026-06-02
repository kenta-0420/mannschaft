# F22.1 市（Market）謝礼決済 — 02. API設計

> 親: [README.md](README.md) ／ 関連: [01_data_model.md](01_data_model.md) / [03_security.md](03_security.md)

---

## 1. エンドポイント一覧

| # | メソッド/パス | 用途 | 認可 |
|---|---|---|---|
| 1 | `POST /api/v1/payment/connect/onboarding-link` | Connect onboarding リンク発行（個人/チーム/組織） | 個人=本人・TEAM/ORG=scope ADMIN |
| 2 | `GET /api/v1/payment/connect/status` | 自分（または指定scope）の Connect 状態 | 個人=本人・TEAM/ORG=scope ADMIN |
| 3 | `PUT /api/v1/teams/{teamId}/recruitment-listings/{id}/payment` ほか | 札の謝礼設定（金額・受領主体） | 札主 scope ADMIN（既存札API拡張） |
| 4 | （内部）応募成立時の与信 | `incrementConfirmed()` フック | イベント駆動・外部API無し |
| 5 | （内部）最終認証時 capture+transfer | `MarketFinalize` confirm フック | イベント駆動・外部API無し |
| 6 | `POST /api/v1/payment/escrow/{id}/refund` | 返金（部分/全額） | 札主 scope ADMIN |
| 7 | `POST /api/v1/webhooks/stripe/connect` | Connect Webhook 受け口 | permitAll（署名検証） |
| 8 | `GET /api/v1/payment/escrow/{id}` | エスクロー取引の状態照会 | 札主 scope ADMIN・受領者本人 |

> パス接頭辞は既存 payment ドメイン（`/api/v1/payment/...`・`/api/v1/webhooks/stripe...`）に揃える。札の謝礼設定は F03.11 既存の Team/Organization 別 Controller に**拡張**で乗せる（市から直接立てない原則）。

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

### 5.1 与信（応募成立 OPEN→FULL）
```
RecruitmentListingEntity.incrementConfirmed() → RecruitmentConfirmedEvent 発火
  ↓ (payment.escrow リスナ)
1. 札が payment_enabled か判定（FALSE なら何もしない）
2. 受領主体の connect_accounts を解決（payee_kind/受領主体ID）
3. payer の Stripe Customer 解決（既存 stripe_customers 再利用 or 新規 createCustomer）
4. StripePaymentProvider.createDestinationPaymentIntent(
     amount=price, currency='jpy', capture_method='manual',
     application_fee_amount=fee, transfer_data.destination=acct_xxx, on_behalf_of=acct_xxx,
     idempotency_key="escrow-{listingId}-{participantId}")
5. escrow_transactions INSERT (status=AUTHORIZED, authorized_at, hold_expires_at=now+最大7日)
   ├─ connect_accounts.payouts_enabled=false → status=HELD（capture 待ち）
   └─ ledger_entries(AUTHORIZE) 追記
```
> 支払者のカード入力は**応募ダイアログで Stripe Checkout/Elements を用いブラウザから直送**（カード番号は自社を通らない・PCI SAQ-A・03 §1）。FE は client_secret を受け取り confirm する。

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

## 6. 返金 / 与信取消

### 6.1 `POST /api/v1/payment/escrow/{id}/refund`
```json
// Request
{ "amount": 5000, "reason": "cancellation", "reasonDetail": "天候中止のため" }
```
**処理**
```
1. 認可: 札主 scope ADMIN のみ（IDOR: escrow.source の scope 所有権検証・03 §4）
2. status 分岐:
   ├─ AUTHORIZED/HELD（capture 前）: PaymentIntent.cancel() → CANCELLED（返金でなく与信取消・支払者に課金なし）
   └─ CAPTURED（capture 後）: Refund.create(amount, idempotency_key="refund-{escrowId}-{seq}")
        → refunds INSERT (status=PENDING) → charge.refunded Webhook で SUCCEEDED 確定
        → amount==escrow.amount なら REFUNDED / 部分なら PARTIALLY_REFUNDED
3. ledger_entries(REFUND or CANCEL) 追記
```
**エラー**
| 条件 | エラー |
|---|---|
| 既に REFUNDED | `PAYMENT_020 ALREADY_REFUNDED` |
| `amount > 残額` | `PAYMENT_021 REFUND_AMOUNT_EXCEEDS` |
| 札主でない | `PAYMENT_001 FORBIDDEN`（403） |

### 6.2 札下げ・期限切れ連携（自動）
`cancelByAdmin()` / `autoCancel()` が `RecruitmentCancelledEvent` を発火 → `payment.escrow` が与信中（AUTHORIZED/HELD）の escrow を `PaymentIntent.cancel()` で取消（支払者課金なし）。capture 済なら全額 Refund（札下げ＝役務不履行のため）。

### 6.3 リコンシリエーション（整合バッチ）
- 15 分間隔で `PaymentIntent.retrieve`／日次で `balance_transaction` を取得し、`ledger_entries` と Stripe balance を突合（F13.1 §8.8 踏襲）。
- 1 取引の借方合計＝貸方合計の検算（01 §3.3）。不一致は**握りつぶさずアラート**（CLAUDE.md 根治原則）。

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

// 与信（Destination Charge + manual capture）
PaymentIntentInfo createDestinationPaymentIntent(
    long amountMinor, String currency, String payerCustomerId,
    long applicationFeeMinor, String destinationAccountId, String idempotencyKey);

void captureManualPaymentIntent(String paymentIntentId, String idempotencyKey);   // capture+transfer
void cancelAuthorization(String paymentIntentId, String idempotencyKey);          // 与信取消
String createPartialRefund(String paymentIntentId, long amountMinor, String reason, String idempotencyKey);

ConnectAccountInfo retrieveConnectAccount(String stripeAccountId);                 // status 同期用

record PaymentIntentInfo(String paymentIntentId, String clientSecret, String status) {}
record ConnectAccountInfo(boolean chargesEnabled, boolean payoutsEnabled, java.util.List<String> requirementsDue) {}
```
> `clientSecret` は FE が Stripe.js で confirm する際に必要（カード直送・PCI SAQ-A）。

---

## 9. 冪等性キーの規約

| 操作 | idempotency_key |
|---|---|
| 与信作成 | `escrow-{listingId}-{participantId}` |
| capture | `capture-{escrowId}` |
| 与信取消 | `cancel-{escrowId}` |
| 返金 | `refund-{escrowId}-{seq}`（部分返金の連番） |
| Webhook 処理 | `stripe_webhook_events.event_id`（DB 一意制約・§4.1） |

> Stripe の idempotency_key（24h 有効）＋ DB の `stripe_webhook_events.event_id` UNIQUE ＋ 札行 `PESSIMISTIC_WRITE` の三重で二重決済を防ぐ。

---

## 10. テスト方針（契約テスト・test-first 先行）

- **認可**: onboarding（個人=本人以外403/チーム=非ADMIN403）、返金（札主以外403）、escrow 照会（無関係 scope → 404 秘匿）。
- **冪等**: 同一 event_id 二重 Webhook → 1 回だけ処理。同一 capture idempotency_key 二重 → 1 回だけ capture。
- **状態遷移**: AUTHORIZED→CAPTURED、AUTHORIZED→CANCELLED、HELD→（onboarding完了）→CAPTURED、CAPTURED→PARTIALLY_REFUNDED/REFUNDED、DISPUTED→先capture。
- **保留**: payouts_enabled=false で与信 → HELD。72h 超過 → CANCELLED ＋ 通知。
- **JPY**: amount=円整数で Stripe へ渡る（ゼロデシマル）。
- **二重払出防止**: 並行 confirm（札行ロック直列化）で capture が 1 回のみ。
- **台帳**: 各 escrow の借方=貸方検算。
