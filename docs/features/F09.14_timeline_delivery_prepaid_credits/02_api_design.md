# F09.14 API設計

> **ステータス**: 🟢 設計完了。HTTPは同期受付、配信・課金確定はjobとwebhookで非同期に行う。

## 1. 共通契約

全APIは`/api/v1`、lowerCamelCase、scope認可を使う。mutationは`Idempotency-Key`必須、同じkeyとrequest hashなら同じ結果、payload差異は409。非所属・scope横断・IDORは404。flag offの新規mutationは403、legacy POST/feedは現行契約を維持する。

### 概算

`POST /timeline/delivery-estimates` request=`{scopeType,scopeId,deliveryScope,postId|null}`、response=`{estimatedRecipientCount,asOf,freePostsUsed,freePostsRemaining,estimatedChargeYen,requiresPaidPermission,paidConfirmationToken:string|null,expiresAt:string|null}`。権限者にだけtokenとexpiresAtを返す。0件は0、未計算はnull。estimateのcount/amount/asOfは監査用の見積値であり、実公開額の上限・課金確定値ではない。

### 投稿とjob

既存`POST /timeline/posts`は201 `PostResponse`互換で、PostResponseへのF09.14追加fieldは`deliveryJobId`,`publicationStatus`,`deliveryJobStatus`だけとする。create requestの追加fieldは`allowPaidAtPublish`と`paidConfirmationToken:string|null`である。confirmationはscope・actor・`deliveryScope`へ束縛し、Tx-Aで一度だけconsumeする。Tx-A前にscope/権限/token形式/同期的に確定できる有料条件だけ409（jobなし）。Tx-A後は残高不足、projection遅延、権限剥奪、配信失敗をHTTP例外にせずjob GET 200で返す。

`allowPaidAtPublish=true`だけが予約有料同意を保存し、そのfuture consent用tokenをscope・actor・`deliveryScope`へ束縛して発行する。公開時に同じ束縛と期限を検証・consumeし、false/省略は有料化時`PUBLISH_BLOCKED`となる。retry requestは`{paidConfirmationToken:string|null,idempotencyKey:string}`で、有料jobまたは別actor retryではtokenを同Txでscope、current SEND権限、actor、idempotencyと検証・consumeする。

| endpoint | status | response/要点 |
|---|---|---|
| `POST /{scope}/{id}/timeline-delivery/jobs/{jobId}/retry` | 202 / 409 | `DeliveryJobResponse`。PROCESSING retryは409、PUBLISH_BLOCKED/FAILURE_RESOLVINGのみ安全なretry |
| `GET /{scope}/{id}/timeline-delivery/jobs/{jobId}` | 200 / 404 | publicationStatus + job status/failureCodeを返す。GETはdrain中も認可後200 |
| `DELETE /{scope}/{id}/timeline-delivery/jobs/{jobId}` | 204 / 409 | PREPARING/PUBLISH_BLOCKEDは直接DELETED、AWAITING_TOPUPのみCANCEL_RESOLVING、PROCESSINGは409 |
| `GET /{scope}/{id}/credits` | 200 / 404 | available/reserved/frozenと無料利用を返す。SYSTEM_ADMINは監査GETのみ |
| `POST /{scope}/{id}/credits/purchases` | 201 / 403 | Stripe Checkout URLとPENDING purchase。scope ADMINのみ |
| `GET /{scope}/{id}/credits/purchases/{purchaseId}` | 200 / 404 | PENDING/PAID/PARTIALLY_REFUNDED/PAYMENT_FAILED/CANCELLED/EXPIRED等を返す |
| `POST /{scope}/{id}/credits/purchases/{purchaseId}/refund-commands` | 202 / 409 | `RefundCommandResponse{commandId,status,liabilityId,refundId:null可,amountYen,createdAt,updatedAt}` |
| `GET /{scope}/{id}/credits/purchases/{purchaseId}/refund-commands/{commandId}` | 200 / 404 | ADMINが同commandを観測。timeoutも同idempotencyで追跡 |
| `GET /{scope}/{id}/timeline-delivery/checkout/return?purchaseId=opaque` | 200 / 404 | queryを信用せず再認可。webhookが入金正本 |

`DeliveryJobResponse`は`publicationStatus`と`status`だけを状態正本とし、別のtop-level配信状態は持たない。statusは`PREPARING|AWAITING_TOPUP|PROCESSING|COMPLETED|FAILURE_RESOLVING|PUBLISH_BLOCKED|CANCEL_RESOLVING|DELETED`。`exactRecipientCount`は未確定null、確定0は0。failureCodeは`PAID_CONFIRMATION_REQUIRED_ASYNC|PAID_PERMISSION_REVOKED_ASYNC|AUTO_TOPUP_REQUIRES_ACTION|CREDIT_INSUFFICIENT|CREDIT_WALLET_FROZEN|DELIVERY_PROCESSING_LOCKED|null`。

## 2. 自動補充・同意

`POST /{scope}/{id}/credits/mandate`、`PATCH`、`DELETE`はscope ADMINのみ。requestはconsenting user/customer/payment methodと`thresholdCredits,refillCredits,monthlyCapCredits,enabled`を含み、owner移譲・同意ADMIN離脱/剥奪・脱退ではrevokeして新規chargeを停止する。`AutoTopupResponse`の設定値・counterはmandate/period-usage由来であることを明記し、pendingを残高に含めない。

必要量は`R=Σ waiting job.requiredRemaining`（createdAt,id順）、available `A`、active pending未割当`P`から計算する。`S=max(0,R-A)`、`assigned=min(S,P)`、`jobNeed=max(0,S-assigned)`。不足時は最低購入50、cap不足なら不足jobだけblocked。R<=Aなら公開を止めずbufferだけ別評価する。active PaymentIntentへwaiting jobを排他的に割り当て、同jobを二重課金しない。

## 3. Stripe webhook ingress

Stripe署名を検証して候補allowlistを判定し、metadataまたはDBのglobal unique checkoutSession/paymentIntent/charge/refund/dispute IDでtimeline専用inboxをclaimする。metadata欠落時も既存routerへ渡す前に安全なglobal ID照合を行う。claim/inbox永続化失敗はretryable 5xx、永続化後のparser/dispatcher失敗は200でdispatcher retry/DLQとする。未claim eventは既存routerへ渡す。

| event | 必須/nullableと処理 |
|---|---|
| `checkout.session.completed` | mode=payment、currency、amount、payment_status必須。paidだけPAID化、unpaidはPENDING |
| `checkout.session.async_payment_succeeded` | purchaseまたはglobal session/PIで同じPAID遷移 |
| `checkout.session.async_payment_failed` | retryable、PENDING/cap pending維持 |
| `checkout.session.expired` | Checkout期限切れの終端、PENDING→CANCELLED |
| `payment_intent.succeeded` | PI object lock + creditedAt CASで一回だけPAID化 |
| `payment_intent.payment_failed` | failure code nullable。retryableなら非終端PENDING |
| `payment_intent.canceled` | cancellation reason nullable。CANCEL_REQUESTEDからCANCELLEDまたはPAYMENT_FAILED、pending解放 |
| `refund.created/updated/failed` | refund status nullable、cumulative amountを正本にliability更新 |
| `charge.dispute.created/updated/closed/funds_withdrawn/funds_reinstated` | dispute status/outcome nullable、freeze/reinstate/lossを累積冪等処理 |

PAID化集合は`checkout.session.completed(payment_status=paid) | async_payment_succeeded | payment_intent.succeeded`の三つだけである。同一purchaseのcreditedAt CASとStripe payment objectで順序逆転・再送を収束する。`payment_failed`/`requires_payment_method`はretryable、`canceled`はdurable cancel command後の終端、expiredはCheckout期限の終端である。requires_actionは再認証UIを出さずcancel commandをenqueueし、CTAは手動補充して再試行とする。

## 4. 同期/非同期エラー

Tx-A前だけHTTP ErrorCodeを返す。`TIMELINE_018`（paid confirmation 409）、`TIMELINE_019`（permission 403）、入力・取消409等がこれに該当する。Tx-A後の残高不足、wallet frozen、権限剥奪、projection遅延、Stripe/配信失敗はHTTP statusを作らずjobの`failureCode`としてGET 200で観測する。`TIMELINE_028 PAID_CONFIRMATION_REQUIRED_ASYNC`、`TIMELINE_029 PAID_PERMISSION_REVOKED_ASYNC`はjob専用であり、実装時に既存ErrorCode採番と照合する。

## 5. 実装時の技術検討事項

- Stripe event全順列、retrieve、outbox lease、署名fixture、F09.13/Escrow非回帰は実装前にmatrixを確定する。
- checkout returnは1/2/5秒後と10秒間隔で最大2分pollするUI契約を04に置くが、入金の正本はwebhookである。
- refund commandはcommand由来Stripe idempotency keyをAPI呼出前にclaimし、応答後refundIdをglobal UNIQUE保存する。timeoutは同keyで再照会する。
