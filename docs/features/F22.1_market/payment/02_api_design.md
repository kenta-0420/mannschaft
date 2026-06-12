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
// 共通: 返金（受取側 ADMIN 操作・支払者負担モデル＝decouple・refund_application_fee:false）
// transferRefundAmount=支払者へ戻す額（transferAmount ベース）。明示 TransferReversal＋reverse_transfer:false の Refund（§6.1）
String refundCharge(UUID escrowId, long transferRefundAmount, String reason, String idempotencyKey);
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
| 6 | `POST /api/v1/payment/escrow/{id}/refund` | 返金（部分/全額・支払者負担モデル＝明示 TransferReversal＋`reverse_transfer:false` の Refund・`refund_application_fee:false`・§6.1） | 共通 | **受取側 scope の ADMIN**（運営非関与・設定A） |
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
| `paymentEnabled=true && price==null` | `PAYMENT_C010 PRICE_REQUIRED`（既存検証を拡張） |
| `paymentEnabled=true && payeeKind==null` | `PAYMENT_C011 PAYEE_REQUIRED` |
| `payeeKind=USER && payeeUserId==null` | `PAYMENT_C012 PAYEE_USER_REQUIRED` |
| 受領主体の `connect_accounts` が READY/payouts_enabled でない | **エラーにせず受理可**（応募成立時に `HELD`・§5）。ただし札立てフォームで警告表示（04 §2） |
| `payeeKind=USER` の対象が札主チームの所属でない | `PAYMENT_C013 PAYEE_NOT_IN_SCOPE`（個人受領者は札主に紐づく者に限定・IDOR 防止） |
| `payeeKind=TEAM/ORG` で受領主体が札主 scope と不一致 | `PAYMENT_C013` |

> 受領者の onboarding が未完了でも**札は立てられる**（応募者を集めながら口座登録を進められる）。実際の払出時点で payouts_enabled を再判定する（§5）。
> 札立てフォームの謝礼額入力は**額面（`face_amount`）**である。支払者の請求額（額面+2.5%）と受取側送金額（額面−2.5%）は §3.5 の計算で導出され、UI に明示する（04 §2/§3）。

---

## 3.5 手数料の計算規約（ランク制・`fee_policies` 率%＋固定額¥・折半50/50固定・マスター確定 2026-06-04）

謝礼・会費・参加費の全てに**共通**で適用する（`PaymentFeeCalculator` に一元化・散在禁止）。**手数料は定数でなく `fee_policies`（率 `percent_rate`＋固定額 `flat_fee_minor`）で解決**し（README §3.4・01 §3.6/§3.7）、額面（`face_amount`）と解決した policy を入力に、円ゼロデシマル・**四捨五入**で計算する。

```
// 入力: face_amount（額面）, policy = FeePolicyResolver で解決した fee_policies 行
total_fee              = round(policy.percent_rate × face_amount) + policy.flat_fee_minor   // 総手数料（率分＋固定額分）
half_fee               = round(total_fee ÷ 2)                                                // 折半（50/50固定）
payer_surcharge        = half_fee                                                            // 支払者上乗せ（総手数料の半分）
amount（課金額）        = face_amount + payer_surcharge                                        // Stripe へ渡す請求額
application_fee_amount  = total_fee                                                           // 総手数料 = Mannschaft が徴収
payee_transfer_amount  = amount − application_fee_amount                                      // 受取側送金額（= face − (total_fee − half_fee)）
stripe_fee（参考）      = round(amount × stripe_fee_rate)                                       // stripe_fee_rate 既定 0.036（設定値）
mannschaft_net（参考）  = application_fee_amount − stripe_fee                                    // Mannschaft 純益（参考）
```

> **PaymentFeeCalculator の改修方針（README §3.4・正典）**: 既存の定数 `PAYER_FEE_RATE=0.025`/`TOTAL_FEE_RATE=0.05` を**撤廃**し、**policy（`percent_rate`/`flat_fee_minor`）を引数注入して計算する純粋関数**へ変える。DB 参照（policy の解決）は呼出側（`FeePolicyResolver`・§3.5.1）の責務とし、`PaymentFeeCalculator` は純粋関数性（状態・外部依存なし）を維持する。**既存テスト（額面10,000→application_fee=500）は DEFAULT policy（率5%＋固定0）で完全不変**（後方互換）。

### 3.5.1 手数料パターンの解決（`FeePolicyResolver`）

`escrow_transactions` 起票（charge/与信/サブスク加入）時、source_kind＋任意 sub_key から適用パターンを解決し、`PaymentFeeCalculator` に渡す。解決した `policy_key` を `escrow_transactions.fee_policy_key`（遡及防止の焼き付け）に記録する。

```java
// payment.FeePolicyResolver（新規・P2-f）— DB 参照はここに閉じる（PaymentFeeCalculator は純粋関数のまま）
FeePolicy resolve(EscrowSourceKind sourceKind, String subKey /* nullable・助っ人=recruitment_category 等 */);
//   解決順序: ① (source_kind, sub_key) 完全一致 → ② (source_kind, sub_key=NULL) 既定 → ③ DEFAULT
//   いずれも fee_policy_assignments.enabled かつ fee_policies.enabled を満たすもの。DEFAULT は終端（削除不可）
```
> `sub_key` の値域は source_kind ごとに異なる（例: `RECRUITMENT` の sub_key＝`recruitment_category` の値）。解決ロジックは本 Resolver に一箇所集約し、文字列直比較を散在させない。

### 3.5.2 安全ガード（必須・少額決済の破綻防止）

固定額（`flat_fee_minor`）混在時、少額の額面では「総手数料 > 額面」となり **Stripe `application_fee_amount ≤ amount` 制約違反**かつ「払った額より手数料が高い」破綻を招く。これを起票前に検証して拒否する（業務上の上限/下限キャップ自体は設けない）。

```
// 必須ガード（起票前）
if (total_fee > face_amount) → reject(PAYMENT_C060 FEE_EXCEEDS_FACE_AMOUNT)
// 同値: application_fee_amount(total_fee) ≤ amount(face + half_fee) は total_fee ≤ face で常に成立
//       （half_fee ≥ 0 ゆえ face + half_fee ≥ face ≥ total_fee）→ chk_et_fee も自動充足
```
> - 「総手数料が額面を超えない」を必須不変条件とする（最低決済額の検証として表現してもよいが、固定額の存在ゆえ額面比較が確実）。
> - 違反は握りつぶさず `ConnectPaymentErrorCode.PAYMENT_C060`（`ERROR_CODE_STATUS_MAP` 登録・422）で拒否し、管理者に「このパターンはこの額面に適用できない（固定額が大きすぎる）」と原因を返す（症状を隠さない・CLAUDE.md 根治原則）。
> - シスアドが `fee_policies` 追加・割当時にも、当該 source_kind の想定最小額面に対し破綻しないかを警告できると望ましい（§11 の CRUD 応答に検証ヒントを含める余地）。

### 3.5.3 テナント別上書きは作らない（将来拡張点）

複雑なテナント別（organization_id 別）の手数料上書きは**今回は作らない**（器も設けない）。`fee_policy_assignments` は全テナント共通の source_kind＋sub_key 解決に留める。将来テナント別が必要になった場合の拡張点としてのみ言及（割当表に `organization_id` を足し解決順序に挟む等）。

### 3.5.4 具体例 — DEFAULT（率5%＋固定0・額面 10,000 円・JPY）

DEFAULT policy（`percent_rate=0.05`/`flat_fee_minor=0`）。total_fee＝`round(0.05×10,000)+0=500`、half_fee＝250。**旧 5% 折半と完全一致**（後方互換）。

| 項目 | 金額 | 計算 | 記録先 |
|---|---|---|---|
| 額面（`face_amount`） | **10,000 円** | 受取側設定 | `escrow_transactions.face_amount` |
| 総手数料（DEFAULT 率5%＋固定0） | **500 円** | `round(0.05 × 10,000) + 0` | （`application_fee_amount`） |
| 支払手数料（折半＝総手数料の半分） | **+250 円** | `round(500 ÷ 2)` | （`amount` に内包） |
| **課金額（`amount`）** | **10,250 円** | 額面 + 支払手数料 | `escrow_transactions.amount`（Stripe 課金額） |
| **総手数料（`application_fee_amount`）** | **500 円** | total_fee そのもの | `escrow_transactions.application_fee_amount` |
| 適用パターン | **`DEFAULT`** | FeePolicyResolver | `escrow_transactions.fee_policy_key`（焼き付け） |
| 受取側送金額 | **9,750 円** | 10,250 − 500 | `ledger_entries`(TRANSFER_OUT) |
| Stripe 実手数料（≈3.6%・課金額基準） | **≈369 円** | `round(10,250 × 0.036)` | `ledger_entries`(FEE)・**Stripe Webhook の実額で記録** |
| **Mannschaft 純益** | **≈131 円**（額面の ≈1.31%） | 500 − 369 | 日次照合で可視化（§6.3） |

### 3.5.5 具体例 — 固定額入りパターン（率3%＋固定100円・額面 10,000 円）

シスアド追加例 `policy_key='RECRUITMENT_HELPER'`（助っ人＝`recruitment_category` 割当・`percent_rate=0.03`/`flat_fee_minor=100`）。total_fee＝`round(0.03×10,000)+100=400`、half_fee＝200。

| 項目 | 金額 | 計算 | 記録先 |
|---|---|---|---|
| 額面 | **10,000 円** | 受取側設定 | `face_amount` |
| 総手数料（率3%＋固定100） | **400 円** | `round(0.03 × 10,000) + 100` | （`application_fee_amount`） |
| 支払手数料（折半） | **+200 円** | `round(400 ÷ 2)` | （`amount` 内包） |
| **課金額（`amount`）** | **10,200 円** | 額面 + 200 | `amount` |
| **総手数料（`application_fee_amount`）** | **400 円** | total_fee | `application_fee_amount` |
| 適用パターン | **`RECRUITMENT_HELPER`** | FeePolicyResolver | `fee_policy_key`（焼き付け） |
| 受取側送金額 | **9,800 円** | 10,200 − 400 | `ledger_entries`(TRANSFER_OUT) |

> - **安全ガード**: `total_fee(400) ≤ face(10,000)` で OK。仮に固定 1,000・率5%・額面 500 なら total_fee＝1,025 > 500 で `PAYMENT_C060` 拒否（§3.5.2）。
> - **遡及防止**: `fee_policy_key='RECRUITMENT_HELPER'` を焼き付け、以後シスアドが当該パターンの率を改定しても本取引は 400 円のまま（README §3.4.2 / 01 §3.2）。
> - `ledger_entries`(FEE) には設定値の概算でなく Stripe Webhook（`balance_transaction`）の実手数料を記録し純益の微変動を可視化（症状を隠さない・§6.3）。
> - `chk_et_fee: application_fee_amount ≤ amount` は安全ガードにより常に充足。

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
4. FeePolicyResolver で policy 解決（source_kind=RECRUITMENT, sub_key=recruitment_category）→ PaymentFeeCalculator で計算
   （face_amount=price → total_fee=round(percent×face)+flat, amount=額面+折半, application_fee=total_fee・§3.5）
   ＋安全ガード（total_fee ≤ face・違反は PAYMENT_C060 FEE_EXCEEDS_FACE_AMOUNT で応募成立をロールバック・§3.5.2）
5. StripePaymentProvider.createDestinationPaymentIntent(
     amount=（額面+折半上乗せ）, currency='jpy', capture_method='manual',   // エスクローモード
     application_fee_amount=（total_fee）, transfer_data.destination=acct_xxx, on_behalf_of=acct_xxx,
     idempotency_key="escrow-{listingId}-{participantId}")
6. escrow_transactions INSERT
     (source_kind=RECRUITMENT, capture_mode=MANUAL, face_amount, amount, application_fee_amount, fee_policy_key,
      status=AUTHORIZED, authorized_at, hold_expires_at=now+最大7日)
   ├─ connect_accounts.payouts_enabled=false → status=HELD（capture 待ち）
   └─ ledger_entries(AUTHORIZE) 追記
```
> 支払者のカード入力は**応募ダイアログで Stripe Checkout/Elements を用いブラウザから直送**（カード番号は自社を通らない・PCI SAQ-A・03 §1）。FE は client_secret を受け取り confirm する。決済確認画面には**手数料内訳（額面/支払手数料2.5%/合計）と「決済手数料は返金されません」**を明示する（04 §3.1）。

### 5.1b 会費（即時モード・MEMBERSHIP・P2-e）
```
F08.2 会費支払い API → ConnectChargeService.createCharge(sourceKind=MEMBERSHIP, captureMode=AUTOMATIC, faceAmount=会費額)
1. 受取側 connect_accounts を解決（チーム/組織の Connect・payee_kind=TEAM/ORG）
2. FeePolicyResolver で policy 解決（source_kind=MEMBERSHIP）→ PaymentFeeCalculator で計算（§3.5・謝礼と共通）＋安全ガード
3. StripePaymentProvider.createDestinationPaymentIntent(
     amount=（額面+折半上乗せ）, currency='jpy', capture_method='automatic',  // 即時モード＝即 capture
     application_fee_amount=（total_fee）, transfer_data.destination=acct_xxx, on_behalf_of=acct_xxx,
     idempotency_key="membership-{memberPaymentId}")
4. escrow_transactions INSERT (source_kind=MEMBERSHIP, capture_mode=AUTOMATIC, fee_policy_key,
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
// Request（amount は両モード共通で「精算額 R＝transferAmount ベース」。0 < amount ≤ transferAmount − 既返金累計。null=全額。
//          feeBearer は手数料負担者 PAYER（既定）/ PAYEE。null=PAYER）
{ "amount": 5000, "feeBearer": "PAYEE", "reason": "cancellation", "reasonDetail": "天候中止のため" }
```

> **返金経済モデル＝feeBearer 2モード（マスター確定・2026-06-03）**: 受取側 scope の ADMIN が返金時に**手数料の負担者**を選択する。`amount`（精算額 R）は両モード共通で **transferAmount ベース**（残額管理・status 遷移・`refunds.amount`・`charge.refunded` webhook 確定はすべて R で行い整合させる）。
>
> **手数料ランク化との整合（2026-06-04）**: 返金計算は**保存済みの `amount`・`application_fee_amount` の差分**（transferAmount = amount − application_fee_amount）で行うため、**手数料が定数か `fee_policies`（率%＋固定額）かに依存しない（rate 非依存）＝ランクが可変でも整合する**。`chk_et_fee`（application_fee ≤ amount）は安全ガード（§3.5.2・total_fee ≤ face）により構造的に維持される。以下は **DEFAULT パターン**（率5%＋固定0）の例:
>
> 額面 10,000 例（chargeAmount=10,250 / transferAmount=9,750 / application_fee=500 / Stripe 実手数料 ≈369）で**全額返金**後の各当事者の到達点:
>
> | 当事者 | モードA＝`PAYER`（既定・支払者都合） | モードB＝`PAYEE`（受取側の落ち度/中止） |
> |---|---|---|
> | 支払者 | 戻りは **transferAmount=9,750**。差額 500 を負担。−10,250+9,750=**−500** | **満額 chargeAmount=10,250** が戻る（落ち度がないので満額）。**±0** |
> | 受取側 | **±0**（受け取った 9,750 を全額巻き戻し） | 送金 9,750 巻き戻し（受取側 −9,750・落ち度の代償） |
> | Mannschaft | **±0**（charge 時純益を維持・application_fee keep） | **1.4% を放棄して中立**（`refund_application_fee:true`）＋**Stripe 手数料 ≈369 を一時負担**（後述の制約） |

> **⚠️ モードB の Stripe 実挙動の制約（数値検証・正直報告・症状を隠さない）**: マスター意図は「モードB では受取側が Stripe 決済手数料（≈369）を負担し Mannschaft±0」だが、**標準 Stripe API のみでこれを返金 1 件ごとに自動成立させることは不可能**であることを実挙動検証で確認した。
> - **Stripe 決済手数料は返金で返らない**（全 payment method 共通・Stripe 公式: refund 自体に手数料はかからないが*元取引の処理手数料は返金されない*）。Destination Charge では platform（Mannschaft）が手数料の支払者であるため、返金時にこの手数料は **platform が被る**。
> - **受取側から手数料分を追加で巻き戻せない**: `TransferReversal` の上限は**元送金額（9,750）**であり、それを超えて受取側残高から ≈369 を引くことはできない（[Transfer Reversals: "reversing a transfer made for a destination charge is allowed only up to the amount of the charge"]）。
> - **受取側残高からの追加徴収（Account Debits）は要件が重い**: 連結口座の*法的同意*＋*追加コスト*＋*platform と連結口座が同一リージョン*が必須で、**返金 1 件ごとの自動操作には適さない**（運用 / 例外処理向き）。
> - **したがって本実装のモードB は標準呼び出し `Refund.create(amount=grossRefund, reverse_transfer=true, refund_application_fee=true)` を採用**し、結果として **Mannschaft が Stripe 手数料 ≈369 を一時負担**する。受取側への最終転嫁は**リコンシリエーション（§6.3）／次回入金からの相殺／運用での Account Debits** に委ねる。一時負担額は `ledger_entries`(C PLATFORM_FEE) に記録して**可視化**する（握り潰さない）。

**処理（feeBearer でモード分岐・capture 後 CAPTURED/PARTIALLY_REFUNDED）**
```
R = 精算額（transferAmount ベース・null=残額全部）

■ モードA＝PAYER（既定・decouple 方式・比例 reverse の落とし穴を回避）
  ① transferId = resolve(PaymentIntent → latest_charge → charge.transfer)   // capture 時の tr_xxx
     （解決不能なら INVALID_ESCROW_STATE で拒否・症状を隠さない）
  ② Transfer.createReversal(transferId, amount=R, key="reversal-{escrowId}-{seq}")  // 受取側から R 巻き戻し（先に実行）
  ③ Refund.create(amount=R, reverse_transfer=false, refund_application_fee=false, key="refund-{escrowId}-{seq}")
     → 支払者へ R 返金（Mannschaft±0・受取側±0・1.4% keep）
  ④ ledger(REFUND): D PAYEE=R / C PAYER=R（借貸一致）

■ モードB＝PAYEE（支払者満額返金＋application_fee 返金＝Mannschaft 中立化）
  ① grossRefund = 支払者へ戻す満額（全額時=chargeAmount、部分時=round(chargeAmount × R / transferAmount)）
  ② Refund.create(amount=grossRefund, reverse_transfer=true, refund_application_fee=true, key="refund-{escrowId}-{seq}")
     → 支払者へ grossRefund 返金 / 送金（比例）巻き戻し / application_fee 返金（Stripe 手数料は platform 一時負担）
     ※ 明示 TransferReversal は呼ばない（reverse_transfer=true が巻き戻しを担う＝二重巻き戻し防止）
  ③ ledger(REFUND): D PAYER=grossRefund / C PAYEE=R ＋ C PLATFORM_FEE=(grossRefund − R)（借貸一致・Mannschaft 放棄/一時負担を可視化）

共通: refunds INSERT (amount=R, status=PENDING) → charge.refunded Webhook で SUCCEEDED 確定
      → 累計(R 基準)==transferAmount なら REFUNDED / 部分なら PARTIALLY_REFUNDED
```

> **部分返金の不変条件**: モードA 部分は「支払者へ R / 受取側 R 巻き戻し」。モードB 部分は「支払者へ R をグロスアップした gross / 受取側 R 比例の手数料負担」。残額管理は両モードとも R（transferAmount 基準）で一致するため、混在返金（同一 escrow で A と B を跨ぐ）でも累計が transferAmount に達した時点で REFUNDED となる。grossRefund の比例丸めは ≤1 円の誤差が出うるが、`ledger_entries`(PLATFORM_FEE) と §6.3 リコンシリで台帳化し可視化する（隠さない）。

**処理（decouple 方式・比例 reverse の落とし穴を回避）**
```
1. 認可: 受取側 scope の ADMIN のみ（IDOR: escrow.payee の scope 所有権検証・03 §4。USER 受領は本波未提供で 404 秘匿）
2. status 分岐:
   ├─ AUTHORIZED/HELD（capture 前）: PaymentIntent.cancel() → CANCELLED
   │     （返金でなく与信取消・支払者に課金なし・即時モード MEMBERSHIP は capture 前が無いため対象外）
   └─ CAPTURED/PARTIALLY_REFUNDED（capture 後）: R = 支払者へ戻す額（transferAmount ベース・null=残額全部）
   │   ① transferId = resolve(PaymentIntent → latest_charge → charge.transfer)   // capture 時の tr_xxx
   │      （解決不能なら INVALID_ESCROW_STATE で拒否・症状を隠さない）
   │   ② Transfer.createReversal(transferId, amount=R, idempotency_key="reversal-{escrowId}-{seq}")  // 受取側から R 巻き戻し（先に実行）
   │   ③ Refund.create(
   │        amount = R,                       // 支払者へ R（=transferAmount ベース）を返金
   │        reverse_transfer = false,         // 比例 reverse は使わない（②で明示巻き戻し済み＝Mannschaft±0）
   │        refund_application_fee = false,   // 徴収済み Mannschaft 手数料は返金しない（設定A・1.4% keep）
   │        idempotency_key="refund-{escrowId}-{seq}")
   │     → refunds INSERT (amount=R, status=PENDING) → charge.refunded Webhook で SUCCEEDED 確定
   │     → 累計==transferAmount なら REFUNDED / 部分なら PARTIALLY_REFUNDED
3. ledger_entries(REFUND or CANCEL) を **監査追記のみ**（自前の逆仕訳ロジックは作らない＝金を動かすのは Stripe）
   REFUND は D PAYEE=R / C PAYER=R（受取側が被る額=支払者へ戻す額・借貸一致）
```

> - **なぜ比例 reverse（`reverse_transfer:true`）を使わないか**: 「返金額=transferAmount(9,750) + `reverse_transfer:true`」だと Stripe は送金を `9,750/10,250=95.12%` でしか比例巻き戻しせず受取側に約 476 残り、Mannschaft が持ち出しになる。逆に「返金額=face(10,000) + 比例 reverse」でも 97.56% 巻き戻しで 238 残る。**boolean 比例 reverse 単独では Mannschaft±0/受取側±0 を同時達成できない**。そこで **②明示 TransferReversal（R）＋③`reverse_transfer:false` の Refund（R）を decouple** し、巻き戻し額＝返金額＝R を**完全一致**させる（Mannschaft±0・受取側±0）。
> - **不変条件**: 任意の返金額 R に対し **(Mannschaft の balance 変化)=0 かつ (受取側が被る額)=(支払者へ戻す額=R)**。支払者は手数料分（支払上乗せ 2.5% + Stripe 決済手数料）を取り戻せない。
> - **巻き戻しを先に**: ②（受取側 → platform）を③（platform → 支払者）より先に実行し、巻き戻し失敗時に支払者返金へ進まない（Mannschaft の一時的持ち出しも防ぐ）。
> - **Stripe 決済手数料（≈3.6%）は返金されない**（Stripe 仕様・`refund_application_fee:false` とは別に Stripe 手数料そのものが戻らない）。返金額が transferAmount ベース（支払上乗せ 2.5% を含まない）であることと併せ、利用規約・決済画面で事前周知（README §3.5.1 / 03 §10 / 04 §3.1）。
> - **受取側残高不足**: 受取側 Connect 残高が巻き戻し額に満たない場合、Stripe がマイナス残高を後続入金/口座引落で**自動回収**する。**Mannschaft には請求が来ない**（03 §6 運用注意）。

**エラー**
| 条件 | エラー |
|---|---|
| 既に REFUNDED | `PAYMENT_C020 ALREADY_REFUNDED`（409） |
| `amount > 残額（transferAmount − 既返金額）`（支払者負担モデル・02 §6.1） | `PAYMENT_C021 REFUND_AMOUNT_EXCEEDS`（422） |
| 受取側 scope ADMIN でない | `PAYMENT_C001 PAYMENT_FORBIDDEN`（403・無関係 scope は 404） |
| `transferId` 解決不能（capture の tr_xxx 不在等） | `PAYMENT_C042 INVALID_ESCROW_STATE`（409・§6.1 ①） |

### 6.2 札下げ・期限切れ連携（自動）
`cancelByAdmin()` / `autoCancel()` が `RecruitmentCancelledEvent` を発火 → `payment.escrow` が与信中（AUTHORIZED/HELD）の escrow を `PaymentIntent.cancel()` で取消（支払者課金なし）。capture 済なら全額返金（§6.1 の decouple 方式＝明示 TransferReversal＋`reverse_transfer:false` の Refund・`refund_application_fee:false`・札下げ＝役務不履行でも支払者負担モデルは同じ＝支払者へ transferAmount を戻す）。

### 6.3 リコンシリエーション（整合バッチ・純益可視化）
- 15 分間隔で `PaymentIntent.retrieve`／日次で `balance_transaction` を取得し、`ledger_entries` と Stripe balance を突合（F13.1 §8.8 踏襲）。
- 1 取引の借方合計＝貸方合計の検算（01 §3.3）。不一致は**握りつぶさずアラート**（CLAUDE.md 根治原則）。
- **純益の微変動可視化**: `ledger_entries`(FEE) に **Stripe Webhook（`balance_transaction.fee`）の実手数料**を記録し、`application_fee_amount`（5%徴収）− Stripe 実手数料＝Mannschaft 純益（≈額面の1.31%）を日次集計。グロスアップ後課金額に対する Stripe 手数料の実額が想定（0.036）からブレた場合も台帳で可視化し、症状を隠さない（README §3.4）。

#### 6.3.1 ModeB 返金で一時負担した実 Stripe 手数料の自動回収（相殺回収）

ModeB（受取側負担）返金では Mannschaft が支払者へ `grossRefund` を満額返金し `refund_application_fee:true` で application_fee を返金するため、元 charge の **実 Stripe 手数料（`grossRefund − R`）を Mannschaft が一時負担**する。この未回収額を payee（受取側 Connect アカウント）×通貨単位の残高表に積み、後続の同 payee 決済の `application_fee_amount` に上乗せして実回収する。

**残高表 `fee_recovery_balances`（V84.001・01 §3.3）:**

| 列 | 型 | 説明 |
|---|---|---|
| `id` | BINARY(16) | PK（UUIDv7・原則6） |
| `connect_account_id` | BINARY(16) | 受取側 Connect 口座（論理参照・FK なし・原則1） |
| `organization_id` | BIGINT UNSIGNED NULL | テナント絞り込み（`AbstractTenantAwareRepository`・原則7） |
| `outstanding_amount` | BIGINT | 未回収残高（minor・署名付き） |
| `currency` | CHAR(3) | minor 母数（既定 `jpy`） |
| `deleted_at` | DATETIME(6) NULL | 論理削除（連結口座切離し時の残高リセット） |

- **UNIQUE**: `uk_frb_account_currency (connect_account_id, currency)`（payee×通貨で物理 1 行・upsert）。

**`RECOVERY` 仕訳の 4 経路（`ledger_entries.entry_type='RECOVERY'`・`recovery_kind` で峻別・V84.002/V84.003）:**

勘定の向き（D/C）だけでは C1/C2 発生計上と A 回収実行/再計上を峻別できない（C1 と A 再計上はともに `D PLATFORM_FEE / C PAYEE / re_`）。各 RECOVERY 行に `recovery_kind` を焼き付け、確実に分離する。

| 経路 | `recovery_kind` | 仕訳（account/direction） | `stripe_object_id` | 意味 |
|---|---|---|---|---|
| **C1 発生計上** | `C1_ACCRUAL` | `D PLATFORM_FEE` / `C PAYEE` | `re_xxx`（Refund ID） | その escrow 自身の ModeB 返金で被った実 Stripe 手数料を未回収残高に計上 |
| **C2 補完** | `C2_COMPLETION` | `D PLATFORM_FEE` / `C PAYEE` | `re_xxx`（Refund ID） | C1 が balance_transaction 未確定（pending）で先送りした分をリコンシリで後追い計上（C1 と同一会計） |
| **A 回収実行** | `A_EXECUTION` | `D PAYEE` / `C PLATFORM_FEE` | `pi_xxx`（PaymentIntent ID） | 他者債務（未回収残高）を当該 charge の application_fee に上乗せして実回収（payee 送金から控除） |
| **A 再計上** | `A_RECAPITALIZE` | `D PLATFORM_FEE` / `C PAYEE` | `re_xxx` または `cancel-<id>` | A で回収を上乗せした charge が ModeB 返金/取消で巻き戻った際、回収実行を打ち消す逆仕訳 |

- 「当該 escrow に上乗せ適用した回収の純額」は **A 経路のみ**（`A_EXECUTION` の `D PAYEE` − `A_RECAPITALIZE` の `C PAYEE`）で導出する（`LedgerEntryRepository.sumAppliedRecoveryNetOnEscrow`）。C1/C2 の発生計上は除外する。
- C2 補完候補（`findModeBRefundEscrowsWithoutRecovery`）は「ModeB 返金記帳あり（`REFUND/D/PAYER`）かつ **C1/C2 発生計上なし**」で判定する（A 経路の RECOVERY が立っている escrow でも自身の C1 が pending なら拾える）。

**自己返金時の合成規則（不変条件・🔴根治）:**

「A で回収を上乗せした charge X が、後で自己 ModeB 返金される」自己返金では、同一返金処理内で次の 2 つが**同一 payee 残高に加算合成**される。

1. **A 再計上**: X に乗っていた回収（A_EXECUTION 純額）を `outstanding` へ戻す（`+applied`）。
2. **C1 発生計上**: X 自身の実 Stripe 手数料を `outstanding` へ計上する（`+recoverable`）。

→ `outstanding += applied + recoverable`（IT シナリオ4: 回収 360 + 自身手数料 400 = **760**）。

二重防御で回収金消失を防ぐ:
- **① 峻別**: `recovery_kind` により `sumAppliedRecoveryNetOnEscrow` が A 経路のみ集計し、C1 の `C PAYEE` が純額に混入しない（旧実装は `360 − 400 = −40 ≤ 0` で A 再計上が早期 return → 回収金消失していた）。
- **② 順序**: `refund()` 内で **A 再計上を C1 発生計上より先に**呼び、Hibernate AUTO フラッシュで C1 行が A 純額の読み取りに混入する余地を断つ。

**不変条件:**
- **`chk_et_fee` 不可侵**: PI の `application_fee = totalFee + recovery ≤ amount`（`recovery ≤ headroom = amount − totalFee` を `FeeRecoveryCalculator` の headroom クランプで保証）。escrow 列の `application_fee_amount` は self の totalFee のまま据え置き、PI の application_fee にのみ上乗せ（隔離原則）。
- **部分回収＋繰越**: `outstanding > headroom` のときは headroom 分のみ回収し、残りを次回に繰り越す。
- **冪等**: 回収実行は当該 escrow の A 純額 > 0 なら skip。新規 charge 経路（既存 escrow は冪等キーで早期 return）でのみ上乗せ計算を行い、Stripe 冪等キーで同一 PI を再取得するため PI への二重上乗せが起きない。

---

## 7. エラーコード一覧（`ConnectPaymentErrorCode`＝`PAYMENT_C0xx` 系・実装正典）

> 本表は実 enum `com.mannschaft.app.payment.connect.ConnectPaymentErrorCode`（R1 #1326 / R2 #1328 main マージ済）の**実コード文字列**と `GlobalExceptionHandler.ERROR_CODE_STATUS_MAP` の**実登録 HTTP ステータス**に一致させる（実装が正典）。F22.1 謝礼/会費決済は本 enum を用いる。既存 `PaymentErrorCode`（`PAYMENT_001`〜`PAYMENT_027`・F08.2 会費）とは文字列が別系統（`C0xx`）で衝突しない。

| 実コード | 定数名 | HTTP | 意味 |
|---|---|---|---|
| `PAYMENT_C001` | `PAYMENT_FORBIDDEN` | 403 | 認可エラー（札主/受領者本人でない・IDOR） |
| `PAYMENT_C002` | `PAYMENT_RESOURCE_NOT_FOUND` | 404 | escrow/connect_account が存在しない（または scope 不一致で秘匿） |
| `PAYMENT_C010` | `PRICE_REQUIRED` | 422 | `payment_enabled` なのに price なし |
| `PAYMENT_C011` | `PAYEE_REQUIRED` | 422 | `payeeKind` なし |
| `PAYMENT_C012` | `PAYEE_USER_REQUIRED` | 422 | `payeeKind=USER` で `payeeUserId` なし |
| `PAYMENT_C013` | `PAYEE_NOT_IN_SCOPE` | 422 | 受領者が札主 scope に紐づかない |
| `PAYMENT_C020` | `ALREADY_REFUNDED` | 409 | 既に返金済み |
| `PAYMENT_C021` | `REFUND_AMOUNT_EXCEEDS` | 422 | 返金額が残額を超過 |
| `PAYMENT_C030` | `ONBOARDING_NOT_READY` | 409 | 払出時に payouts 不可（HELD 化で通常はエラーにしないが手動操作時の保険） |
| `PAYMENT_C040` | `WEBHOOK_SIGNATURE_INVALID` | 400 | Webhook 署名検証失敗 |
| `PAYMENT_C041` | `AUTHORIZATION_FAILED` | 409 | 与信失敗（Stripe 側エラー・カード拒否）。応募成立をロールバックし応募者へ通知 |
| `PAYMENT_C042` | `INVALID_ESCROW_STATE` | 409 | 払出不能な状態（CANCELLED/REFUNDED 後等）からの payout/返金要求 |
| `PAYMENT_C043` | `CAPTURE_FAILED` | 409 | capture（払出）失敗（Stripe 側エラー） |
| `PAYMENT_C050` | `STRIPE_API_ERROR` | 500 | Stripe API 通信失敗（`Severity.ERROR` 既定 500） |
| `PAYMENT_C060` | `FEE_EXCEEDS_FACE_AMOUNT` | 422 | **安全ガード（R1）**: 総手数料 > 額面（固定額が大きすぎ・少額決済の破綻）・§3.5.2 |
| `PAYMENT_C051` | `FEE_POLICY_NOT_FOUND` | 404 | シスアド CRUD で存在しない policy_key を参照・§11 |
| `PAYMENT_C052` | `FEE_POLICY_DEFAULT_IMMUTABLE` | 409 | `DEFAULT` パターンの削除/無効化を拒否（解決の終端・最後の砦）・§11 |
| `PAYMENT_C053` | `FEE_POLICY_INVALID_RATE` | 422 | `percent_rate` が `[0,1)` 外・率と固定額がともに 0（手数料ゼロ）・policy_key 形式違反・§11 |
| `PAYMENT_C054` | `FEE_POLICY_ALREADY_EXISTS` | 409 | 既存 policy_key で POST（重複）。更新は `PUT /{policyKey}` へ誘導・§11 |
| `PAYMENT_C055` | `FEE_POLICY_ASSIGNMENT_DUPLICATE` | 409 | 割当 `(source_kind, sub_key, organization_id)` UNIQUE 違反・§11 |
| `PAYMENT_C056` | `FEE_POLICY_ASSIGNMENT_POLICY_DISABLED` | 422 | 割当先 policy が無効（`enabled=FALSE`）。存在しないものは `PAYMENT_C051`（404）で区別・§11 |

> **実装注記（Connect 系コードの命名・番号の連続性）**: 実コードは概念順でなく**衝突回避の都合で番号が前後する**（安全ガード `FEE_EXCEEDS_FACE_AMOUNT` は実コード `PAYMENT_C060`。`PAYMENT_C050` は `STRIPE_API_ERROR`/500 が先に確保済みのため、安全ガードは R2 シスアド CRUD 用に予約した `C051`〜`C056` とも衝突しない `C060` を採った）。`PAYMENT_011/013` 等の旧番号（`C` なし）を本表で用いていたのは概念対応の設計記載であり、**実コードは全て `PAYMENT_C0xx` 系**（R1/R2 で確定・本表が正典）。後続フェーズも `ConnectPaymentErrorCode`（`PAYMENT_C0xx` 系）を継続使用すること。各コードの HTTP は `GlobalExceptionHandler.ERROR_CODE_STATUS_MAP` に明示登録済（登録漏れは 400/500 既定へフォールバックするため要注意・#1279 前科）。

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
// 返金（支払者負担モデル・decouple 方式）: reverse_transfer=false で支払者へ R を返金（refund_application_fee=false=1.4% keep）
ConnectRefundInfo createConnectRefund(String paymentIntentId, long amountMinor, String reason,
    boolean reverseTransfer, boolean refundApplicationFee, String idempotencyKey);
// 受取側送金を明示的に巻き戻す（Mannschaft±0/受取側±0・比例 reverse の取りこぼし回避）
String resolveTransferIdFromPaymentIntent(String paymentIntentId);                // PI → latest_charge → charge.transfer（tr_xxx）
void reverseTransfer(String transferId, long amountMinor, String idempotencyKey); // Transfer.createReversal(amount=R)

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
- **返金（設定A・支払者負担モデル・§6.1）**: capture 済は decouple 方式＝明示 TransferReversal(R)＋`reverse_transfer=false`/`refund_application_fee=false` の Refund(R) で、支払者へ transferAmount(9,750) を戻し受取側±0・Mannschaft±0・1.4% keep。残額管理は **transferAmount 基準**。capture 前は cancel（課金なし）。
- **保留**: payouts_enabled=false で与信 → HELD。72h 超過 → CANCELLED ＋ 通知。
- **JPY**: amount/application_fee=円整数で Stripe へ渡る（ゼロデシマル）。
- **二重払出防止**: 並行 confirm（札行ロック直列化）で capture が 1 回のみ。
- **台帳**: 各 escrow の借方=貸方検算。FEE は Stripe 実手数料で記録（純益可視化・§6.3）。
- **手数料ランク（§3.5・P2-f）**: DEFAULT policy で額面10,000→application_fee=500/amount=10,250（既存テスト不変）。固定額入り（率3%＋固定100）で額面10,000→total_fee=400/amount=10,200/受取9,800。安全ガード境界（固定1,000・率5%・額面500→total_fee=1,025 > 500 で `PAYMENT_C060` FEE_EXCEEDS_FACE_AMOUNT）。解決順序（完全一致→source_kind既定→DEFAULT）。**遡及防止**（charge 後に policy 率を改定しても焼き付けた `fee_policy_key` の率で固定）。`PaymentFeeCalculator` は純粋関数（policy 注入）・DB 参照は `FeePolicyResolver`。

---

## 11. シスアド手数料パターン管理（`/system-admin/fee-policies`・P2-f）

手数料パターン（`fee_policies`）と割当（`fee_policy_assignments`）を**システム管理者が随時 CRUD** する。既存 `SystemAdminNavFeaturesController` と同型（`@PreAuthorize` SYSTEM_ADMIN・`{policyKey}` 自然キー）。

| # | メソッド/パス | 用途 | 認可 |
|---|---|---|---|
| 1 | `GET /api/v1/system-admin/fee-policies` | パターン一覧（率・固定額・enabled・割当数） | SYSTEM_ADMIN |
| 2 | `GET /api/v1/system-admin/fee-policies/{policyKey}` | パターン詳細 | SYSTEM_ADMIN |
| 3 | `POST /api/v1/system-admin/fee-policies` | パターン新規（policyKey/displayName/percentRate/flatFeeMinor/description） | SYSTEM_ADMIN |
| 4 | `PUT /api/v1/system-admin/fee-policies/{policyKey}` | パターン更新（率・固定額・enabled・説明）。**改定は新規徴収のみ反映**（遡及しない・§3.5.5） | SYSTEM_ADMIN |
| 5 | `DELETE /api/v1/system-admin/fee-policies/{policyKey}` | パターン無効化（`enabled=false`・**`DEFAULT` は不可** `PAYMENT_C052`） | SYSTEM_ADMIN |
| 6 | `GET /api/v1/system-admin/fee-policy-assignments` | 割当一覧（source_kind＋sub_key → policy_key） | SYSTEM_ADMIN |
| 7 | `POST /api/v1/system-admin/fee-policy-assignments` | 割当作成（sourceKind/subKey?/policyKey） | SYSTEM_ADMIN |
| 8 | `DELETE /api/v1/system-admin/fee-policy-assignments/{id}` | 割当解除（論理削除） | SYSTEM_ADMIN |

**Request（パターン作成 #3）**
```json
{ "policyKey": "RECRUITMENT_HELPER", "displayName": "助っ人募集（率3%＋固定100円）",
  "percentRate": 0.03, "flatFeeMinor": 100, "description": "助っ人 recruitment_category 向け" }
```
**Request（割当作成 #7・助っ人＝recruitment_category）**
```json
{ "sourceKind": "RECRUITMENT", "subKey": "helper", "policyKey": "RECRUITMENT_HELPER" }
```

**検証・エラー**
- `percentRate` ∈ [0,1)・かつ `percentRate>0 || flatFeeMinor>0`（手数料ゼロ禁止）でなければ `PAYMENT_C053`（422）。
- 存在しない policyKey 参照は `PAYMENT_C051`（404）。`DEFAULT` の削除/無効化は `PAYMENT_C052`（409）。
- 作成/更新応答に**安全ガードの想定検証ヒント**（当該 source_kind の想定最小額面に対し total_fee ≤ face が成立するか・§3.5.2）を含めると、シスアドが固定額の付けすぎを事前に把握できる（推奨）。
- 操作は監査ログ（既存 `AuditLogService`・料率改定は監査対象）。i18n（管理画面文言・04 §6 に `systemAdmin.feePolicy.*` 骨子）。
