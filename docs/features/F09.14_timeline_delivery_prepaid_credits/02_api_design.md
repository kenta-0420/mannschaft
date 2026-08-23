# F09.14 API設計

> **ステータス**: 🟡 設計精査中

## 1. 共通契約

パス中の `{scopeType}` は小文字 `teams` / `organizations`、`{scopeId}` は `int64`、JSON は lowerCamelCase、成功は `{ "data": ... }`、失敗は既存 `ErrorResponse` とする。日時は offset付き ISO-8601 string、金額・credit・件数は JavaScript安全範囲を超えうるため JSON string（`"12345"`）で返す。`boolean` と enum は null不可、`asOf` と optional外部IDだけ nullable。本文の `scopeType` enum は `TEAM` / `ORGANIZATION`。

scopeの存在・所属・権限は各 Team/Organization Service と ContentVisibilityResolver 経由で解決する。権限不成立・異scopeは存在を漏らさず404、認証なし401とする。CSRF、入力長、レート制限は [04](04_security_ui_i18n.md) に従う。

## 2. 投稿前の概算・公開

### `POST /api/v1/timeline/delivery-estimates`

投稿フォームの初回表示・`deliveryScope`変更時に呼ぶ。既存投稿を保存しない。

権限: 既存の当該scope投稿権限。`VIEW_TIMELINE_COST` を持たない通常投稿者には `recipientEstimate` と `asOf` だけを返し、金額・残高・利用数はnullにする。

```json
// request
{
  "scopeType": "ORGANIZATION",
  "scopeId": 42,
  "deliveryScope": "DESCENDANTS"
}
// response data
{
  "recipientEstimate": "1240",
  "asOf": "2026-08-23T11:30:00+09:00",
  "wouldCountAsEligiblePost": true,
  "freePostsUsed": "100",
  "freePostsRemaining": "0",
  "wouldRequirePaidPermission": true,
  "estimatedPaidCredits": "1240",
  "estimatedAmountYen": "1240",
  "availableCredits": "3000",
  "canSendPaid": true,
  "paidConfirmationToken": "550e8400-e29b-41d4-a716-446655440000",
  "confirmationExpiresAt": "2026-08-23T11:35:00+09:00"
}
```

`deliveryScope` は `DIRECT` / `CHILDREN` / `DESCENDANTS`。TEAM は `DIRECT` のみ、PUBLIC等は400。`paidConfirmationToken` / `confirmationExpiresAt` は paid見込かつ `SEND_PAID_TIMELINE`保有時のみ non-null。それ以外はnullである。`recipientEstimate:string` と `asOf:string` は常にnon-null、`freePostsUsed/freePostsRemaining/wouldRequirePaidPermission/estimatedPaidCredits/estimatedAmountYen/availableCredits/canSendPaid` は `VIEW_TIMELINE_COST`なしならnullである。数値は正確な確定を約束しない。

### 既存 `POST /api/v1/timeline/posts` の拡張

既存作成リクエストへ任意で次を追加する。返信・下書き・予約作成では `paidConfirmationToken` を禁止（400）。

| field | 型 / null | 規則 |
|---|---|---|
| deliveryScope | string / nullable | TEAMは`DIRECT`、ORGは3値。未指定は既存規定値 |
| paidConfirmationToken | UUID string / nullable | 有料として公開される場合に必須。概算API発行・5分以内・scope/actor/deliveryScope束縛 |
| scheduledAt | date-time string / nullable | 既存契約。予約時は実公開まで課金しない |

実公開は `202 Accepted` で次を返す。同期に投稿だけを作り `PUBLISHED` と見せることはしない。

```json
{
  "data": {
    "postId": 123,
    "deliveryJobId": "018f...",
    "deliveryStatus": "PROCESSING",
    "billingMode": "PAID",
    "exactRecipientCount": "1243",
    "reservedCredits": "1243",
    "estimatedRecipientCount": "1240",
    "publishedAt": "2026-08-23T11:31:00+09:00"
  }
}
```

`deliveryJobId` は受信者0件・対象外投稿ではnull、`billingMode` は`FREE`/`PAID`/`NONE`、`reservedCredits` は FREE/NONEで`"0"`。レスポンスの投稿表現にも `deliveryStatus: string|null`、`exactRecipientCount: string|null` は追加するが、金額・財布情報は投稿閲覧者に返さない。

## 3. ダッシュボード・財布

以下は `{scopeType}` path を `teams` / `organizations` に展開する。

| method / path | 権限 | request | response data |
|---|---|---|---|
| GET `/{scopeType}/{scopeId}/timeline-delivery/summary` | ADMIN または `VIEW_TIMELINE_COST` | なし | `recipientEstimate:string`,`asOf:string`,`periodStart:string`,`freePostsUsed:string`,`freePostsRemaining:string`,`paidDeliveryCount:string`,`canSendPaid:boolean`,`status:string`; 金融詳細は非ADMINならnull |
| GET `/{scopeType}/{scopeId}/timeline-delivery/wallet` | ADMIN | なし | `availableCredits:string`,`reservedCredits:string`,`status:ACTIVE|FROZEN|CLOSED|SETTLEMENT_BLOCKED`,`firstManualPurchaseAt:string|null`,`autoTopup:AutoTopupResponse`,`purchases:PurchaseSummary[]` |
| GET `/{scopeType}/{scopeId}/timeline-delivery/ledger?cursor=&size=` | ADMIN | cursor nullable string、size int 1..100 default20 | cursor page。`entries[]`: `id:string`,`entryType:string`,`creditsDelta:string`,`amountYen:string`,`occurredAt:string`,`postId:string|null`,`recipientCount:string|null`,`actorDisplayName:string|null`; recipient user ID は返さない |
| PUT `/{scopeType}/{scopeId}/timeline-delivery/auto-topup` | ADMIN | 下表 | `AutoTopupResponse` |
| POST `/{scopeType}/{scopeId}/timeline-delivery/checkout` | ADMIN | 下表 | `purchaseId:string`,`checkoutUrl:string`,`expiresAt:string` |
| POST `/{scopeType}/{scopeId}/timeline-delivery/purchases/{purchaseId}/cancel` | ADMIN | `{ "idempotencyKey": "uuid" }` | `PurchaseResponse` |
| POST `/{scopeType}/{scopeId}/timeline-delivery/jobs/{jobId}/retry` | ADMIN または `SEND_PAID_TIMELINE`かつ作成者 | `{ "idempotencyKey": "uuid" }` | `DeliveryJobResponse` |
| GET `/{scopeType}/{scopeId}/timeline-delivery/jobs/{jobId}` | ADMIN、作成者、または `VIEW_TIMELINE_COST` | なし | `DeliveryJobResponse` |

`AutoTopupResponse`: `enabled:boolean`, `thresholdCredits:string|null`, `refillCredits:string|null`, `monthlyCapCredits:string|null`, `usedThisMonthCredits:string`, `monthStart:string|null`, `canEnable:boolean`, `disabledReason:string|null`。

auto-topup更新requestは以下。`enabled=true`時は3数値すべて必須、`thresholdCredits>=1`、`refillCredits>=50`、`monthlyCapCredits>=refillCredits`。初回手動購入前は `enabled=true` を拒否する。

```json
{ "enabled": true, "thresholdCredits": "500", "refillCredits": "1000", "monthlyCapCredits": "10000" }
```

checkout request: `{ "credits": "500", "idempotencyKey": "uuid" }`。`credits` は50以上の整数、上限はサービス設定（初期`1000000`）以下。`amountYen=credits`、税込JPY、割引なしである。StripeのJPY最小課金50円に合わせるため、財布では1 credit単位で消費できるが購入は50 credit以上とする。

`PurchaseSummary`: `id:string`,`purchaseKind:MANUAL|AUTO_TOPUP`,`status:string`,`creditsPurchased:string`,`remainingCredits:string`,`amountYen:string`,`paidAt:string|null`,`expiresAt:string|null`,`refundable:boolean`,`createdAt:string`。完全未使用の PAID 購入のみ `refundable=true`。

`DeliveryJobResponse` は `id:string`,`postId:string`,`billingMode:FREE|PAID|NONE`,`status:PROCESSING|COMPLETED|FAILURE_RESOLVING|SCHEDULED_BLOCKED|NONE|DELETED`,`estimatedRecipientCount:string`,`exactRecipientCount:string`,`reservedCredits:string`,`capturedCredits:string`,`refundedCredits:string`,`pendingRecipientCount:string`,`deliveredRecipientCount:string`,`undeliverableRecipientCount:string`,`attemptCount:number`,`nextAttemptAt:string|null`,`failureCode:string|null`,`createdAt:string`,`completedAt:string|null`。金額、recipient ID、Stripe情報は返さない。`PurchaseResponse` は `PurchaseSummary` の全fieldに `cancelledAt:string|null`,`refundedAt:string|null`,`refundAmountYen:string`,`checkoutUrl:string|null` を加えたものとする。

## 4. Stripe webhook（内部・署名必須）

既存 `POST /api/v1/webhooks/stripe` を拡張し、Stripe raw body署名検証とイベントID冪等を既存実装から再利用する。公開APIに purchase の `paid` 状態を直接変更する経路は作らない。

| Stripe event | metadata | 動作 |
|---|---|---|
| `checkout.session.completed` | `timelineCreditPurchaseId` | PAID化、期限設定、available加算、PURCHASE元帳 |
| `checkout.session.expired` | 同上 | PENDINGをCANCELLED |
| `payment_intent.payment_failed` | 同上 | PENDING/auto補充失敗、通知 |
| `charge.dispute.created` / `charge.dispute.funds_withdrawn` | purchase IDまたはpayment intent | dispute作成、未使用credit凍結、used分recovery、送信・auto停止 |
| `charge.dispute.closed` / `charge.dispute.funds_reinstated` | 同上 | won/reinstatedなら解除、lostならSETTLEMENT_BLOCKED維持 |
| `refund.created` / `refund.updated` | purchase ID | 返金状態・liabilityを確定 |

イベント種別・metadata不整合は成功200で捨てず、監査記録とアラートを残して既存の再試行/デッドレター方針に従う。Stripe APIで返金する際は idempotency key を purchase / refund reason に束縛する。

## 5. エラーコード予約

`origin/main`（507264fb6）での `TIMELINE` 現在最大は `TIMELINE_017` である。したがって下表の `TIMELINE_018`〜`TIMELINE_027` を設計上予約する。実装開始時とマージ直前に全 `*ErrorCode.java` を再grepし、並行追加との衝突時は未マージ側が再採番する。クライアント起因はすべて`WARN`、外部Stripe/永続障害のみ`ERROR`とする。

| 仮定数 | HTTP | Severity | 発生条件 |
|---|---:|---|---|
| TIMELINE_018 PAID_DELIVERY_CONFIRMATION_REQUIRED | 402 | WARN | 有料化したがtokenなし/期限切れ/見積不一致 |
| TIMELINE_019 PAID_DELIVERY_PERMISSION_DENIED | 403 | WARN | `SEND_PAID_TIMELINE`なし |
| TIMELINE_020 CREDIT_INSUFFICIENT | 402 | WARN | exact件数に対する予約不能。全rollback |
| TIMELINE_021 CREDIT_WALLET_FROZEN | 409 | WARN | dispute/closed/settlementで送信不可 |
| TIMELINE_022 AUTO_TOPUP_INVALID | 400 | WARN | 初回手動購入前・threshold/cap不正 |
| TIMELINE_023 PURCHASE_NOT_CANCELLABLE | 409 | WARN | 一部でも使用済み/返金済み |
| TIMELINE_024 DELIVERY_PROCESSING_LOCKED | 409 | WARN | PROCESSING/FAILURE_RESOLVING中の編集・削除 |
| TIMELINE_025 DELIVERY_RETRY_NOT_ALLOWED | 409 | WARN | 最終化済み/権限なし/attempt上限 |
| TIMELINE_026 DELIVERY_JOB_FAILED | 500 | ERROR | 最終ジョブ障害（API pollでは状態として200） |
| TIMELINE_027 CREDIT_STRIPE_FAILURE | 502 | ERROR | Checkout/Refund外部障害 |

HTTP statusの個別上書きは `GlobalExceptionHandler` に明示する。403/404のscope秘匿、402の支払要求、409の状態競合を混同しない。
