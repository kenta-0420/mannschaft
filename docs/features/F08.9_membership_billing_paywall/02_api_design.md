# F08.9 — 02 API設計

> money 移動は F22.1 `ConnectChargeService`/`payment.connect`/`payment.escrow` を呼ぶ。本書は membership ドメインの API（代理払い・後見切替・継続/期別・協会請求・ペイウォール・集計・領収書）を定義。
> 認可の詳細は [03_security](03_security.md)。

---

## 0. 共通

- ベースパス：`/api/v1`
- レスポンス封筒：`ApiResponse<T>`（既存規約）
- 冪等性：決済起票系は `Idempotency-Key` ヘッダ必須（Stripe idempotency_key へ橋渡し）。Webhook は `stripe_webhook_events.event_id` UNIQUE（既存）。
- 代理コンテキスト：後見切替中は `X-Proxy-For-User-Id`（子）ヘッダを付与（F14.1 `ProxyInputContextFilter` 拡張）。

---

## 1. 払い手による決済（払い手≠受益者）

### 1.1 受益者を指定して会費を決済（即時・Connect）

```
POST /api/v1/payment-items/{itemId}/checkout
Body: { beneficiaryUserId: number, idempotencyKey?: string }
Headers: Idempotency-Key
```
- **払い手の確定**：払い手は常に `SecurityUtils.getCurrentUserId()`（＝実際にログインしている人）。**後見切替セッション中（`X-Proxy-For-User-Id` 付き）でも払い手は保護者のまま**（子になりすまさない）。この場合 `payer_relationship=GUARDIAN_PROXY` を記録し、決済が「子の自己払い」と誤読されないようにする。`beneficiaryUserId` は明示パラメータ（切替中は子＝`X-Proxy-For-User-Id` と一致を検証）。
- 認可：払い手が `beneficiaryUserId` に対し代理払い可（§03_security §2）。本人なら `beneficiaryUserId==self`。
- 処理：`ConnectChargeService.charge(MEMBERSHIP, AUTOMATIC, faceAmount, payeeConnectAccountId, payerStripeCustomerId, idempotencyKey)` を呼び、`member_payments`（PENDING→Webhook で PAID）＋`escrow_transaction_id` 連結。
- レスポンス：`CheckoutResponse { checkoutUrl | clientSecret, memberPaymentId }`
- エラー：`MEMBERSHIP_PAYER_NOT_AUTHORIZED`(403)／`MEMBERSHIP_ALREADY_PAID`(409)／`CONNECT_ACCOUNT_NOT_READY`(409)／`PAYMENT_ITEM_INACTIVE`(422)。
- **受領者 Connect 口座が READY でない場合**：払い手へは「このチーム/組織は現在お支払いの受け取り準備中です。しばらくお待ちください」と返し（払い手向け文言・04 §3）、受領者へは onboarding 督促通知（恒久/一時の別は `onboarding_status` で判定し、`DISABLED`=恒久、`PENDING/ONBOARDING/RESTRICTED`=一時）。定期監視で `is_active` な会費項目の受領口座が非 READY なものをアラート。

### 1.2 後見まとめ支払い（複数の子の会費を一括）

```
GET  /api/v1/me/payable-dues
```
- 認証ユーザーが**払える対象だけ**（本人＋後見下の子＋有効 grant のある受益者）を返す。**権原のない受益者は一切含めない**（他人の未払いを列挙させない＝IDOR 防止・03_security §2）。受益者・チーム/組織・項目・金額・期限・継続/期別区分に加え、**既に他の払い手（別の保護者/本人）が支払い済みの項目は `alreadyPaid` で示す**（共同親権の二重払い・取り違えを防ぐ）。
- レスポンス：`PayableDuesResponse { items: [{ beneficiaryUserId, beneficiaryDisplayName, scope, paymentItemId, name, faceAmount, payerSurcharge, totalCharge, dueDate, kind(ONE_TIME|RECURRING|TERM), authorizationVia, alreadyPaid: boolean, paidBy?: { userId, displayName }, paidAt? }] }`

```
POST /api/v1/me/payable-dues/bulk-checkout
Body: { selections: [{ paymentItemId, beneficiaryUserId }], idempotencyKey }
```
- 選択した複数会費を**1セッションでまとめて決済**（受領者ごとに destination 振り分け）。各明細を個別 `member_payments` として起票し、まとめの領収書はそれぞれ受領者名義で発行。
- **起票直前に各明細を再認可**（一覧取得後に権原が失効/支払い済みに変わる可能性があるため）：明細ごとに `authorizePayment(payer, beneficiary, item)` と `existsValidPaidPayment` を**都度評価**し、権原喪失/支払い済みの明細は**スキップして結果に理由を返す**（部分成功）。確認画面で「誰の・どの会費を・いくら」を明細表示してから決済（受益者の取り違え防止）。
- レスポンス：`BulkCheckoutResponse { checkoutUrl, lines: [{ paymentItemId, beneficiaryUserId, memberPaymentId, accepted: boolean, skipReason? }] }`

---

## 2. 後見切替セッション（acting-as・年齢段階ゲート）

### 2.1 切替可能な子の一覧

```
GET /api/v1/me/guardianship/switchable-children
```
- 認証ユーザーが**後見切替できる子**（保護者リンク有効 かつ 国別ポリシーが `switchAllowed=true`）を返す。`switchAllowed=false`（自立段階）の子は **含めない**（切替封印）。
- 段階判定は `GuardianshipAgePolicy`（国別・03_security §3.1）。`stageKey` は国依存の i18n ラベルキー（日本＝`elementary`/`junior_high`、国により異なる）。
- レスポンス：`SwitchableChildrenResponse { children: [{ childUserId, displayName, stageKey, switchAllowed: true }] , blockedChildren: [{ childUserId, displayName, stageKey, switchAllowed: false, reason }] }`

### 2.2 切替開始 / 終了

```
POST   /api/v1/me/guardianship/switch
Body: { childUserId }
DELETE /api/v1/me/guardianship/switch    # 切替終了（本人へ復帰）
```
- 開始：年齢ゲート（`GuardianshipAgePolicyRegistry.forCountry(child.country_code).resolve(...).switchAllowed == true`）と保護者リンク有効を検証。成功で代理セッションを確立し、以降のリクエストに `X-Proxy-For-User-Id=childUserId` を付与（クライアントが保持）。F14.1 `proxy_input_records` に「保護者X→子Y 切替開始」を記録。
- 終了：代理コンテキスト解除・監査記録。
- エラー：`GUARDIANSHIP_SWITCH_AGE_LOCKED`(403・中学生以降)／`GUARDIANSHIP_LINK_NOT_FOUND`(403)。
- **JWT は再発行しない**（actor は保護者のまま・`subjectUserId` に子）。なりすまし防止：切替中の操作は代理として監査され、子の認証情報（パスワード変更・2FA）は**切替では操作不可**（03_security §3）。
- 用語統一：リクエスト時検証は `ProxyInputContextFilter`（F14.1・既存）の後見切替拡張で行い、Service 層は同 Filter が確立する `ProxyInputContext`（RequestScope Bean）を参照する。

### 2.3 自立移行（中学進学で切替が封じられる前後のUX）— 必須

切替が「ある日突然封印される」だけでは、それまで保護者任せだった子が**自分のアカウントにログインできない**事故が起きる。これを防ぐ移行フローを必須とする。

```
GET  /api/v1/me/guardianship/children/{childUserId}/independence-status
POST /api/v1/me/guardianship/children/{childUserId}/handover/initiate   # 引き継ぎ開始（パス設定メール送付）
```
- **進学予告**：子が国別ポリシーの「自立段階に入る直前」（`GuardianshipAgePolicy` が封印境界として返す日付の手前）になったら、保護者へ**3ヶ月前から事前通知**「◯月からお子さまが自立します。ログイン情報の引き継ぎをお願いします」。境界日もポリシーが返す（日本＝年度末、国により誕生日基準等）。
- **引き継ぎ導線**：`handover/initiate` で子のメール（無ければ保護者が子のメールを登録）へ**パスワード設定リンク**を送付（F01.9 のメール確認・パスワードリセット基盤を流用）。子は自分でパスワード/2FA を設定し、以後は本人ログイン。
- **封印の発火**：国別ポリシーの境界日（日本＝年度替わり4/1）で `switchAllowed=false` となり切替が 403。封印後も**保護者は会費の代理払いだけは継続可**（§3 の保護者リンク経由・切替なしで）。
- **未引き継ぎ時の保険**：封印時点で子がパスワード未設定なら、子のメールへ「あなたのアカウントへようこそ。パスワードを設定してください」を自動送付（取り残し防止）。保護者の会費代理払いは引き続き機能するため**会費滞納は起きない**。

#### 実装（P3c-2・2026-06-05）

- **境界日メソッド**：`GuardianshipAgePolicy.sealDate(birthDate, clock)` を追加（JP/Default 両実装）。`switchAllowed` が `false` に変わる最初の日を返す（JP＝満12歳に達する年度の翌4/1・Default＝満13歳の誕生日）。`clock` 非依存で生年月日から一意に定まる（既に封印済みの子は過去日を返す）。`resolve` の境界と整合（境界日当日に `switchAllowed=false`）。
- **`GET .../independence-status`**：`IndependenceStatusResponse { childUserId, stageKey, switchAllowed, sealDate, passwordSet }`。`passwordSet` は `users.password_hash` の有無（引き継ぎ完了の目安）。**呼び出し元が当該子の有効な保護者でない場合は 403**（`GUARDIANSHIP_LINK_NOT_FOUND`＝`MEMBERSHIP_BILLING_005`・IDOR 防止）。封印済み（`switchAllowed=false`）でも例外にせず段階・境界日・パスワード設定有無を返す（状況把握用）。生年月日解決不能の子は安全側（`switchAllowed=false`・`stageKey=independent`・`sealDate=null`）。
- **`POST .../handover/initiate`**：Body `{ childEmail? }`。子メールへパスワード設定リンクを送付（`AuthPasswordResetService.requestPasswordReset` を流用＝`PasswordResetRequestedEvent`→`EmailOutboxService.enqueue` 経由・F09.18）。**childEmail 規則**：子に既存（ルーティング可能な）メールあり×childEmail 指定 → 400（上書き拒否・`MEMBERSHIP_BILLING_006`／メール変更フローの迂回防止）。子にメールあり×指定なし → 既存メールへ送付。子にメールなし（内部プレースホルダ `*.mannschaft.internal`）×指定あり → 重複チェック（`existsByEmail`・重複は `AUTH_013`）後 `users.email` へ登録して送付。子にメールなし×指定なし → 400（`MEMBERSHIP_BILLING_006`）。**acting-as（後見切替セッション）中は 403**（保護者本人の権原で行う引き継ぎゆえ `AuthenticationCriticalOperationGuard.assertNotActingAs()` を適用・03_security §3.2 の精神）。監査は `audit_logs`（`GUARDIANSHIP_HANDOVER_INITIATED`・metadata に `registeredNewEmail`）。
#### 実装（第三波・P3c-3・2026-06-05）

自立移行の保険として日次バッチ 2 本を追加した（いずれも `Clock` 注入で date-pin テスト可能・`@SchedulerLock` で多重起動防止）。

- **進学予告バッチ**（`GuardianshipProgressionNoticeBatchService`・`guardianship-progression-notice-batch`・毎日 03:00 JST）：
  parental_consent（APPROVED）＋care_links（ACTIVE PARENT）の全 (保護者, 子) ペアをページング走査し、子の `sealDate`（境界日）を算出。
  `today ∈ [sealDate.minusMonths(3), sealDate)`（半開区間）かつ未送信の保護者へ
  「◯月からお子さまが自立します。ログイン情報の引き継ぎをお願いします」を通知する。
  チャネルは**アプリ内通知**（`NotificationHelper.notify` 正準経路・F04.11 統合インボックスに載る）＋
  **メール**（F09.18 outbox・templateKind `GUARDIANSHIP_PROGRESSION_NOTICE`・保護者メールがルーティング可能な場合のみ）。
- **封印時未設定メールバッチ**（`GuardianshipSealUnsetPasswordBatchService`・`guardianship-seal-unset-password-batch`・毎日 03:30 JST）：
  `sealDate <= today` かつ `users.password_hash` 未設定（`GuardianshipHandoverService` と同一のパスワード設定有無判定）の子へ
  パスワード設定メールを自動送付（取り残し防止）。送付は `AuthPasswordResetService.requestPasswordReset` を流用し outbox 経由。
  子のメールが内部プレースホルダ（`*.mannschaft.internal`）の場合は**送付不能としてスキップ＋件数をログに可視化**（症状を隠さない）。
- **重複送信防止**：専用テーブル `guardianship_transition_notifications`（UUIDv7・BINARY(16)・クロスドメインFKなし・Flyway V74.20260605000020）で
  `(notification_kind, recipient_user_id, child_user_id, seal_date)` を UNIQUE 化し、同一（受信者×子×境界日×種別）で 1 回限りに統制する。
  既存 notification 系には (受信者,子,境界日,種別) で 1 回限りを保証する送信記録が無く（notifications は送信ログで UNIQUE なし／
  email_outbox の idempotency_key はメールにしか効かずアプリ内通知の冪等化に使えない）ため新設。
  各バッチは「送信記録を先に保存 → UNIQUE 競合（並行/時刻境界）を `DataIntegrityViolationException` で検知 → 競合時は送信せずスキップ」で
  二重送信を物理的に排除する。
- `users.email` は NOT NULL UNIQUE ゆえ「メールなし」は内部プレースホルダ運用を前提とする（管理子アカウント本実装は将来課題）。

---

## 3. 代理払い許可（第三者・非後見）

```
POST   /api/v1/me/payment-proxy-grants/invite     # 受益者が払い手を招待（トークン or in-app）
Body: { payerUserIdOrEmail, paymentItemId?, effectiveUntil? }
POST   /api/v1/payment-proxy-grants/{token}/accept # 払い手が受諾
GET    /api/v1/me/payment-proxy-grants             # 受益者/払い手が自分の grant 一覧
DELETE /api/v1/me/payment-proxy-grants/{id}        # 取消（受益者 or 払い手）
```
- 後見（保護者）経由は **grant 不要**ゆえ本 API は対象外（祖父母・スポンサー等のみ）。
- レスポンス：`PaymentProxyGrantResponse { id, beneficiaryUserId, payerUserId, paymentItemId?, status, effectiveFrom, effectiveUntil }`

---

## 4. 継続課金（Subscription ＋ invoice 上書き）

### 4.1 加入 / 解約

```
POST   /api/v1/payment-items/{itemId}/subscribe
Body: { beneficiaryUserId, billingAnchorDay?, paymentMethodSetup: <SetupIntent結果> }
DELETE /api/v1/membership-subscriptions/{id}        # 期末解約（cancel_at_period_end=true）
GET    /api/v1/me/membership-subscriptions           # 自分が払い手の継続課金一覧
GET    /api/v1/teams/{id}/membership-subscriptions    # 管理者：チームの継続課金一覧
```
- `subscribe`：`is_recurring=true` 項目のみ。`MembershipSubscriptionService.create(...)` が SetupIntent で保存した PM・受領者 Connect 口座・`billing_anchor_day` で Stripe Subscription を作成し、`membership_subscriptions(status=PENDING)` を起票。加入時に `FeePolicyResolver(MEMBERSHIP)` で解決した `fee_policy_key` を焼き付け（遡及防止・README §4.2）。
- **会費額の固定（price-lock）**：加入時の額面で固定する。受領者が会費を値上げしても**既存サブスクは加入時 price のまま**継続し、値上げは新規加入者のみに適用。既存者へは「会費改定のお知らせ」を確認必須通知（F04.9）で送り、「新価格で継続する／解約する」を選ばせる移行フロー（管理者が改定 price で新項目を発行→既存者に乗り換え導線）。サイレントな自動値上げはしない。**手数料パターン（`fee_policy_key`）も加入時に固定**（料率改定は新規加入のみ反映・遡及しない）。
- 解約：`cancel_at_period_end=true`（**期末まで利用可・日割り返金なし・期末前は再有効化可**）。即時解約は別途（返金は受取側 ADMIN の F22.1 フロー）。UI には**○月○日まで利用可**と日付を明記（04 §2）。

### 4.3 今月スキップ／再開（pause_collection・マスター確定 2026-06-04）

```
POST /api/v1/membership-subscriptions/{id}/skip     # 今月スキップ
POST /api/v1/membership-subscriptions/{id}/resume   # スキップ解除（再開）
```
- **skip**：`MembershipSubscriptionService.skip(id)` が Stripe `Subscription.update(pause_collection={behavior:'void', resumes_at: 次回サイクル+1})` を呼び、`membership_subscriptions.skip_until` に再開予定日をセット。**スキップ月は invoice が void → `invoice.paid` が発火せず `valid_until` を延ばさない**（閲覧も延びない＝ペイウォール無改修で整合・README §4.5）。`status` は `ACTIVE` のまま（解約とは独立）。
- **resume**：`pause_collection` 解除＋`skip_until` クリア。次サイクルから通常課金・延長再開。
- 認可：払い手本人 / 後見保護者（サブスク所有権 `payer_user_id`・03 §1）。
- エラー：`SUBSCRIPTION_NOT_ACTIVE`（409・PENDING/CANCELLED/EXPIRED ではスキップ不可）／`SUBSCRIPTION_ALREADY_SKIPPED`（409・既に skip_until セット済）。
- **UX（04 §2）**：「今月スキップ／解約（○月○日まで利用可）／再開」を継続課金管理に出し、**次回課金日・利用期限を明示**＋確認ダイアログ。i18n 6言語。

### 4.2 Stripe Webhook フロー（継続）

```
POST /api/v1/webhooks/stripe   （既存 StripeWebhookController を拡張）
```

| イベント | 処理 |
|---|---|
| `invoice.created` | **★固定手数料上書き**：該当 subscription の `face_amount` と焼き付けた `fee_policy_key`（F22.1 `fee_policies` で `total_fee=round(percent×face)+flat`・DEFAULT なら `round(face×0.05)`）を算出し、その invoice の `application_fee_amount` を `POST /v1/invoices/{id}` で固定上書き。**スキップ月（pause_collection void）は invoice 自体が void ゆえ上書き対象外** |
| `invoice.paid` | `escrow_transaction(MEMBERSHIP, CAPTURED)`＋`ledger_entries` 起票・`member_payments(PAID)` 生成・受益者の `valid_until` を1サイクル延長・`membership_subscriptions.current_period_*` 更新 |
| `invoice.payment_failed` | `membership_subscriptions.status=PAST_DUE`・払い手へ「お支払いが一時失敗しました。Stripe からのカード更新メールをご確認ください」通知（§6）・grace カウント開始・Stripe smart retries に委譲 |
| `invoice.paid`（再試行成功） | `PAST_DUE → ACTIVE` 復帰・`valid_until` を現在から1サイクル延長・ペイウォール復活 |
| `customer.subscription.deleted` | `status=CANCELLED`・以降ペイウォール失効 |

**状態遷移（継続課金）**

```
PENDING ──(初回 invoice.paid)──▶ ACTIVE
ACTIVE ──(invoice.payment_failed)──▶ PAST_DUE ──(再試行 invoice.paid)──▶ ACTIVE
PAST_DUE ──(grace 超過 = valid_until + payment_items.grace_period_days < 今日)──▶ ペイウォール失効
PAST_DUE ──(Stripe 再試行尽き subscription.deleted)──▶ CANCELLED
ACTIVE/PAST_DUE ──(期末解約 cancel_at_period_end)──▶ 期末に CANCELLED
```
- **grace の出所**：既存 `payment_items.grace_period_days`（V8.010・実在）を用いる。`PAST_DUE` でも `valid_until + grace_period_days >= 今日` の間はペイウォール閲覧可（既存 `existsValidPaidPayment` の判定式と同一・二重定義しない）。失効トリガーは「期限切れ」一本に統一（PAST_DUE は督促状態であって即失効ではない）。
- **smart retries の役割分担**：カード失効/残高不足の再試行・カード更新督促メールは**Stripe が自動**。Mannschaft は `invoice.payment_failed` 受信で状態反映＋アプリ内通知のみ（Stripe の dunning と二重送信しない文言調整）。
- 冪等性：`stripe_webhook_events.event_id` UNIQUE（既存）＋ subscription 行 `PESSIMISTIC_WRITE`。擬似コード：

```java
@Transactional
public void onWebhook(Event ev) {
    try { webhookEventRepo.save(new StripeWebhookEventEntity(ev.getId())); }   // event_id UNIQUE 冪等ゲート
    catch (DataIntegrityViolationException dup) { return; }                    // 処理済み/処理中は即 return（握りつぶしでなく冪等）
    var sub = subscriptionRepo.lockByStripeSubscriptionId(stripeSubId);        // PESSIMISTIC_WRITE
    // ev 種別で分岐（invoice.created=手数料上書き / invoice.paid=起票・延長 / ...）。失敗は記録＋再試行、握りつぶさない
}
```
- **退避策（PoC 不成立時）**：`MembershipSubscriptionService` を自前バッチ実装に差し替え。`@Scheduled`＋ShedLock が `status=ACTIVE AND current_period_end<=今日` を拾い off_session PaymentIntent（固定 application_fee_amount）で都度決済。Webhook 4本のうち `invoice.created` 上書きが不要になる。

---

## 5. 期別課金（単発）

```
POST /api/v1/payment-items/{itemId}/checkout
Body: { beneficiaryUserId }   # itemId.type=TERM
```
- §1.1 と同経路（単発 destination charge・固定 application_fee_amount）。有効期間は `term_starts_on`〜`term_ends_on` に一致。サブスク・Webhook の invoice 上書きは不要。

---

## 6. ペイウォール判定

```
GET /api/v1/content-gates/check
Query: contentType, contentId, beneficiaryUserId?(既定=自分)
```
- レスポンス：`GateCheckResponse { accessible: boolean, requiredItems: [{ paymentItemId, name, faceAmount, satisfied: boolean }], titleHidden: boolean }`
- 判定：`contentType,contentId` に紐づく全 `content_payment_gates` の payment_items について `existsValidPaidPayment(beneficiaryUserId, itemId)`。全充足で `accessible=true`。
- F00 連結：blog/お知らせのリゾルバ（`evaluateCustom`）が `PaymentGateService.isAccessibleByBeneficiary(viewerUserId, ref)` を内部呼び。可視性(visibility) と ペイウォール の **AND**。
- 既存の content gate 設定 API（`Team/OrganizationContentPaymentGateController`）は流用、受益者キー判定に統一。

---

## 7. 協会→加盟チーム請求

```
POST   /api/v1/organizations/{orgId}/payment-requests           # 発行（DRAFT）
PATCH  /api/v1/organizations/{orgId}/payment-requests/{id}/send # 配信（SENT・通知一斉送信）
GET    /api/v1/organizations/{orgId}/payment-requests           # 協会：自分の発行一覧・集計
GET    /api/v1/teams/{teamId}/payment-requests                  # チーム：受信した請求一覧
POST   /api/v1/teams/{teamId}/payment-requests/{id}/pay         # チーム管理者が支払い（Connect・payer=TEAM 案3）
PATCH  /api/v1/organizations/{orgId}/payment-requests/{id}/cancel
GET    /api/v1/teams/{teamId}/payment-advances                  # 立替/精算記録一覧（案3・§2.5）
POST   /api/v1/teams/{teamId}/payment-advances/{id}/confirm-settlement  # 精算確認（F04.9 確認必須通知から）
```
- 発行：`payment_requests(DRAFT)`。`send` で `ConfirmableNotificationService.send(teamAdminUserIds)`（`actionUrl`＝支払い画面・`deadlineAt`＝due_date・自動リマインド）。`PaymentRequestInboxAdapter` で inbox 集約。
- **支払い（payer=TEAM 案3・README §6.3）**：`ConnectChargeService.charge(...)` で `payer_scope_kind=TEAM`/`payee_kind=ORG`。**Stripe の課金 Customer は操作した当該チーム ADMIN 個人の `stripe_customers`**（チームの法人 Customer は持たない）。`status=PAID`・`escrow_transaction_id` 連結。同時に `team_payment_advances` を **`PENDING` で起票**（`payer_user_id`＝操作 ADMIN・`team_id`・`advanced_amount`＝課金額・`payment_request_id`/`escrow_transaction_id` 連結）。**領収書はチーム名義**（`on_behalf_of`＝協会）。手数料は `fee_policies`（協会請求パターン or DEFAULT・README §6.1）で解決。
- **精算確認**：チームから ADMIN へ精算（立替金の返金）が行われたら、`confirm-settlement` で `team_payment_advances.settlement_status=SETTLED`・`settled_at`・`settled_confirmed_by` を記録。精算依頼は **F04.9 確認必須通知**でチーム ADMIN（または会計担当）へ配信。
- 認可：発行＝協会 ADMIN／支払い＝当該チーム ADMIN／精算確認＝当該チーム ADMIN（03_security §1）。
- エラー：`PAYMENT_REQUEST_NOT_FOR_THIS_TEAM`(403)／`PAYMENT_REQUEST_ALREADY_PAID`(409)／`PAYMENT_REQUEST_CANCELLED`(409)。

---

## 8. 可視化・集計・領収書

### 8.1 集計（拡張）
```
GET /api/v1/teams/{id}/payment-summary                 # 既存拡張：払い手/受益者・3区分・期別
GET /api/v1/teams/{id}/payment-items/{itemId}/payments  # 明細（払い手列・受益者列・状態）
GET /api/v1/teams/{id}/payment-items/{itemId}/payments/export  # CSV（BOM付UTF-8・既存拡張）
```
- 状態3区分：`UNPAID`/`PAID`/`EXPIRED`（`valid_until + grace_period_days < CURDATE()`）。
- 継続課金：次回請求日・`PAST_DUE` をハイライト。

### 8.2 領収書（受領者名義）
```
GET /api/v1/member-payments/{id}/receipt        # 受益者/払い手向け：会費領収書（受領者名義・金額のみ）
GET /api/v1/teams/{id}/fee-statements?period=YYYY-MM   # 受領者向け：Mannschaft 名義の月次手数料明細
```
- 会費領収書：`stripe_receipt_url`（受領者ブランド）優先、無ければ自前 PDF（F12.1）。税内訳/登録番号は**拡張枠**（既定非表示・`NoOpTaxPolicy`）。
- **名義の出所**：`stripe_receipt_url` の表示名は Stripe Connect onboarding 登録情報に依存し**Mannschaft からは制御不可**。自前 PDF を出す場合の名義は `organizations.name` / `teams.name`（個人運営チームは onboarding 時の屋号/法人名＝Connect 登録名に揃える）を用い、Stripe 表示名との不一致を避ける。
- 手数料明細：当月の `application_fee_amount` 合計を Mannschaft 名義で（仕入税額控除の枠・税からくり）。

---

## 9. DTO 一覧（骨子）

- `CheckoutResponse`／`BulkCheckoutResponse`／`PayableDuesResponse`
- `SwitchableChildrenResponse`／`PaymentProxyGrantResponse`
- `MembershipSubscriptionResponse { id, beneficiaryUserId, payerUserId, paymentItemId, billingInterval, status, currentPeriodEnd, cancelAtPeriodEnd, skipUntil, feePolicyKey }`
- `PaymentRequestResponse { id, issuerScope, payerScope, title, faceAmount, dueDate, status, paidAt }`
- `TeamPaymentAdvanceResponse { id, teamId, payerUserId, paymentRequestId, advancedAmount, advancedAt, settlementStatus, settledAt }`
- `GateCheckResponse`／`PaymentSummaryResponse(拡張)`／`ReceiptResponse`

---

## 10. エラーコード（`MembershipBillingErrorCode`・新規）

| コード | HTTP | 意味 |
|---|---|---|
| `MEMBERSHIP_PAYER_NOT_AUTHORIZED` | 403 | 払い手が受益者の代理払い権原を欠く |
| `GUARDIANSHIP_SWITCH_AGE_LOCKED` | 403 | 中学生以降の子は切替不可 |
| `GUARDIANSHIP_LINK_NOT_FOUND` | 403 | 有効な保護者リンクなし |
| `PAYMENT_PROXY_GRANT_EXPIRED` | 403 | 代理払い許可が失効 |
| `MEMBERSHIP_ALREADY_PAID` | 409 | 受益者×項目に有効な支払い済 |
| `CONNECT_ACCOUNT_NOT_READY` | 409 | 受領者の Connect 口座が READY でない |
| `SUBSCRIPTION_INVOICE_FEE_OVERRIDE_FAILED` | 500 | invoice 手数料上書き失敗（要再試行・監視） |
| `PAYMENT_REQUEST_NOT_FOR_THIS_TEAM` | 403 | 請求先チーム不一致 |
| `PAYMENT_REQUEST_ALREADY_PAID` | 409 | 請求が支払い済 |
| `SUBSCRIPTION_NOT_ACTIVE` | 409 | スキップ/再開対象が ACTIVE でない |
| `SUBSCRIPTION_ALREADY_SKIPPED` | 409 | 既に今月スキップ済（skip_until セット済） |
| `ADVANCE_ALREADY_SETTLED` | 409 | 立替が既に精算済（重複確認防止） |

---

## 11. 冪等性・障害方針（根治原則）

- 決済起票：`Idempotency-Key` 必須・Stripe へ橋渡し。二重押下で二重課金しない。
- Webhook：`event_id` UNIQUE で二重処理を封じ、subscription/payment 行を悲観ロック。
- **症状を隠さない**：invoice 上書き失敗は握りつぶさず `SUBSCRIPTION_INVOICE_FEE_OVERRIDE_FAILED` を記録し再試行＋アラート（手数料取りこぼし＝Mannschaft 損失を可視化）。`reverse_transfer`/`refund_application_fee` の設定齟齬は起票時に検証。
- Connect 口座未 READY（`payouts_enabled=false`）の受領者への会費は **HELD でなくエラー返却**（即時モードゆえ保留しない）。受領者へ onboarding 督促。
