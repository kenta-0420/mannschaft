# F09.14 API設計

> **ステータス**: 🟢 設計完了

## 1. 共通契約

パス中の `{scopeType}` は小文字 `teams` / `organizations`、`{scopeId}` は既存FEルートと同じ slug 文字列または数値文字列で、Service層で内部 `Long` IDへ解決する。JSON は lowerCamelCase、成功は `{ "data": ... }`、失敗は既存 `ErrorResponse` とする。日時は offset付き ISO-8601 string、金額・credit・件数は JSON integer number（Java側`long`、上限は設定値でJS安全整数以内）で返す。`boolean` と enum は null不可、optional外部IDだけ nullable。本文の `scopeType` enum は `TEAM` / `ORGANIZATION`。

scopeの存在・所属・権限は各 Team/Organization Service と既存 `TimelinePostVisibilityAccessGuard` 経由で解決する。origin/mainのJavadocどおりTIMELINE_POSTはF00 `ContentVisibilityResolver`実装を持たないため、本機能でF00を新設せず、guardへsnapshot述語を追加する。Controller/Service認可番人の委譲深度契約は維持する。権限不成立・異scopeは存在を漏らさず404、認証なし401とする。CSRF、入力長、レート制限は [04](04_security_ui_i18n.md) に従う。

## 2. 投稿前の概算・公開

### `POST /api/v1/timeline/delivery-estimates`

投稿フォームの初回表示・`deliveryScope`変更時に呼ぶ。既存投稿を保存しない。

権限: 既存の当該scope投稿権限。通常投稿者には `recipientEstimate` と `asOf` だけを返す。`VIEW_TIMELINE_COST` 保有者には当月usageを追加し、`SEND_PAID_TIMELINE` 保有者には有料確認に必要な `estimatedPaidCredits`、`estimatedAmountYen`、`canSendPaid`、tokenを追加する。wallet残高・購入・返金等の金融詳細はADMIN専用APIだけで返す。

```json
// request
{
  "scopeType": "ORGANIZATION",
  "scopeId": "42",
  "deliveryScope": "DESCENDANTS"
}
// response data
{
  "recipientEstimate": 1240,
  "asOf": "2026-08-23T11:30:00+09:00",
  "wouldCountAsEligiblePost": true,
  "freePostsUsed": 100,
  "freePostsRemaining": 0,
  "wouldRequirePaidPermission": true,
  "estimatedPaidCredits": 1240,
  "estimatedAmountYen": 1240,
  "canSendPaid": true,
  "paidConfirmationToken": "550e8400-e29b-41d4-a716-446655440000",
  "confirmationExpiresAt": "2026-08-23T11:35:00+09:00"
}
```

`deliveryScope` は `DIRECT` / `CHILDREN` / `DESCENDANTS`。TEAM は `DIRECT` のみ、PUBLIC等は400。`recipientEstimate:number` と `asOf:string` は常にnon-null。`freePostsUsed/freePostsRemaining/wouldRequirePaidPermission` は `VIEW_TIMELINE_COST`、`SEND_PAID_TIMELINE` またはADMINでnon-null。`estimatedPaidCredits/estimatedAmountYen/canSendPaid/paidConfirmationToken/confirmationExpiresAt` はpaid見込かつ `SEND_PAID_TIMELINE` またはADMINでnon-null、それ以外はnull。`availableCredits` はこの概算APIから削除し、ADMIN専用wallet APIでのみ返す。数値は概算であり公開時の確定を約束しない。

### 既存 `POST /api/v1/timeline/posts` の後方互換拡張

実コードは `TimelinePostController@RequestMapping("/api/v1/timeline/posts")` の `POST /api/v1/timeline/posts`、201 `ApiResponse<PostResponse>` である。`CreatePostRequest.scopeId` は数値でなくslugも受理する `String` で、`TimelineScopeIdResolver` が内部Longへ解決する。これを `/api/v1/timeline` へ変更せず、既存201の `PostResponse` に下記optional fieldを追加する。返信・下書きではpaid fieldを禁止する。予約投稿は作成者が予約時に有料化の可能性とdeliveryScopeへ同意し、その永続同意を実公開時に検証する。自動投稿だけがADMINのscope policyを使う。

| field | 型 / null | 規則 |
|---|---|---|
| deliveryScope | string / nullable | TEAMは`DIRECT`、ORGは3値。未指定は既存規定値 |
| paidConfirmationToken | UUID string / nullable | 有料として公開される場合に必須。概算API発行・5分以内・scope/actor/deliveryScope束縛 |
| scheduledAt | date-time string / nullable | 既存契約。予約時は実公開まで課金しない |
| idempotencyKey | UUID string / nullable | legacy無料投稿は未指定可（server-generated request identity、再送dedupe保証なし）。paid confirmation、新FE、retryではrequired |

即時投稿は既存どおり`201 Created`でPostResponseを返すが、初期 `publicationStatus=PREPARING`、`deliveryStatus=PREPARING`で未公開である。同期にrecipientを列挙せず、workerがsnapshot/reserveをcommitした時だけ`PUBLISHED`にする。`PUBLISH_BLOCKED`/`FAILURE_RESOLVING`はrecordとして残り、未公開なら作成者はretry/deleteできる。

```json
{
  "data": { "id": 123, "scope": { "scopeType": "ORGANIZATION", "scopeId": 42, "name": null, "slug": null }, "author": { "userId": 9, "socialProfileId": null, "postedAsType": "USER", "postedAsId": null }, "content": { "content": "本文", "parentId": null, "repostOfId": null, "status": "PUBLISHED", "scheduledAt": null, "isPinned": false }, "stats": { "repostCount": 0, "reactionCount": 0, "replyCount": 0, "attachmentCount": 0, "editCount": 0 }, "audit": { "createdAt": "2026-08-23T12:00:00+09:00", "updatedAt": "2026-08-23T12:00:00+09:00" }, "user": null, "postedAs": null, "systemPostType": null, "attachments": [], "publicationStatus": "PREPARING", "deliveryJob": { "id": "018f...", "status": "PREPARING", "billingMode": "NONE", "estimatedRecipientCount": 1240, "exactRecipientCount": null } }
}
```

PostResponseの既存全fieldは維持する。対象TEAM/ORGトップレベルの`deliveryJob`はnon-null（0 recipientでも`status=COMPLETED`）、対象外投稿だけnull。job IDは`deliveryJob.id`だけに置く。追加fieldは`publicationStatus:PREPARING|PUBLISHED|PUBLISH_BLOCKED|null`と`deliveryJob:DeliveryJobSummary|null`であり、PREPARINGのexactRecipientCountはnull。金額・財布情報は投稿閲覧者に返さない。

## 3. ダッシュボード・財布

以下は `{scopeType}` path を `teams` / `organizations` に展開する。

| method / path | 権限 | request | response data |
|---|---|---|---|
| GET `/{scopeType}/{scopeId}/timeline-delivery/summary` | ADMIN、`VIEW_TIMELINE_COST`、または`SEND_PAID_TIMELINE` | なし | `recipientEstimate:number`,`asOf:string`,`periodStart:string`,`freePostsUsed:number`,`freePostsRemaining:number`,`paidDeliveryCount:number`,`canSendPaid:boolean`,`status:string`; 全field non-null。金融詳細は含めない |
| GET `/{scopeType}/{scopeId}/timeline-delivery/wallet` | ADMIN | なし | `availableCredits:number`,`reservedCredits:number`,`status:ACTIVE|FROZEN|SETTLEMENT_BLOCKED|CLOSING|CLOSED`,`firstManualPurchaseAt:string|null`,`autoTopup:AutoTopupResponse`,`purchases:PurchaseSummary[]` |
| GET `/{scopeType}/{scopeId}/timeline-delivery/ledger?cursor=&size=` | ADMIN | cursor nullable string、size int 1..100 default20 | cursor page。`entries[]`: `id:string`,`entryType:string`,`creditsDelta:number`,`amountYen:number`,`occurredAt:string`,`postId:number|null`,`recipientCount:number|null`,`actorDisplayName:string|null`; recipient user ID は返さない |
| PUT `/{scopeType}/{scopeId}/timeline-delivery/auto-topup` | ADMIN | 下表 | `AutoTopupResponse` |
| POST `/{scopeType}/{scopeId}/timeline-delivery/checkout` | ADMIN | 下表 | `purchaseId:string`,`checkoutUrl:string`,`expiresAt:string` |
| POST `/{scopeType}/{scopeId}/timeline-delivery/purchases/{purchaseId}/cancel` | ADMIN | `{ "idempotencyKey": "uuid" }` | `PurchaseResponse` |
| POST `/{scopeType}/{scopeId}/timeline-delivery/jobs/{jobId}/retry` | ADMIN または `SEND_PAID_TIMELINE`かつ作成者 | `{ "idempotencyKey": "uuid" }` | `DeliveryJobResponse` |
| GET `/{scopeType}/{scopeId}/timeline-delivery/jobs/{jobId}` | ADMIN、作成者、または `VIEW_TIMELINE_COST` | なし | `DeliveryJobResponse` |
| POST `/api/v1/system-admin/batches/timeline-scheduled-publish/run` | SYSTEM_ADMIN | `{ "before": "date-time|null", "limit": 1..1000 }` | `acceptedCount:number`,`alreadyQueuedCount:number`; schedulerと同じdue selectorを起動 |

`AutoTopupResponse`: `enabled:boolean`, `thresholdCredits:number|null`, `refillCredits:number|null`, `monthlyCapCredits:number|null`, `usedThisMonthCredits:number`, `monthStart:string|null`, `canEnable:boolean`, `disabledReason:string|null`。

auto-topup更新requestは以下。`enabled=true`時は3数値すべて必須、`thresholdCredits>=1`、`refillCredits>=50`、`monthlyCapCredits>=refillCredits`。初回手動購入前は `enabled=true` を拒否する。

```json
{ "enabled": true, "thresholdCredits": 500, "refillCredits": 1000, "monthlyCapCredits": 10000 }
```

checkout request: `{ "credits": 500, "idempotencyKey": "uuid" }`。`credits` は50以上の整数、ADMIN設定 `mannschaft.timeline-delivery.max-purchase-credits`（初期1000000）以下。`amountYen=credits`、税込JPY、割引なしである。StripeのJPY最小課金50円に合わせるため、財布では1 credit単位で消費できるが購入は50 credit以上とする。

`PurchaseSummary`: `id:string`,`purchaseKind:MANUAL|AUTO_TOPUP`,`status:string`,`creditsPurchased:number`,`remainingCredits:number`,`amountYen:number`,`paidAt:string|null`,`expiresAt:string|null`,`refundable:boolean`,`createdAt:string`。完全未使用の PAID 購入のみ `refundable=true`。

`DeliveryJobResponse` は `id:string`,`postId:number`,`billingMode:FREE|PAID|NONE`,`status:PREPARING|AWAITING_TOPUP|PROCESSING|COMPLETED|FAILURE_RESOLVING|PUBLISH_BLOCKED|DELETED`,`estimatedRecipientCount:number`,`exactRecipientCount:number|null`,`reservedCredits:number`,`capturedCredits:number`,`refundedCredits:number`,`pendingRecipientCount:number`,`deliveredRecipientCount:number`,`undeliverableRecipientCount:number`,`attemptCount:number`,`nextAttemptAt:string|null`,`failureCode:string|null`,`createdAt:string`,`completedAt:string|null`。`exactRecipientCount`はsnapshot commit前だけnull、`completedAt`と`failureCode`は状態によりnull、それ以外はnon-null。金額、recipient ID、Stripe情報は返さない。`PurchaseResponse` は `PurchaseSummary` の全fieldに `cancelledAt:string|null`,`refundedAt:string|null`,`refundAmountYen:number`,`checkoutUrl:string|null` を加えたものとする。

## 4. Stripe webhook（内部・署名必須）

既存 `POST /api/v1/webhooks/stripe` の署名検証だけを流用し、既存一般例外を200に握りつぶす振舞い・汎用metadata解釈は再利用しない。`TimelineStripeEventParser`は共通の`eventId/apiVersion/livemode/type/metadata.timelineCreditPurchaseId(UUID)/paymentIntentId/chargeId`に加え、disputeでは`disputeId/status/outcome/amount/currency`、refundでは`refundId/status/amount/currency`を正規DTOの必須fieldとして検証する。`timeline_stripe_webhook_inbox`（UUIDv7 id、stripe_event_id UNIQUE、api_version、livemode、payload_hash、status=`RECEIVED/PROCESSING/PROCESSED/FAILED`、attempt、next_attempt_at、lease、監査列）へ保存し、dispatcherだけがpurchaseを更新する。purchase照合はmetadata UUID、paymentIntent一意、charge一意の順で、曖昧照合・未知status/currencyはFAILED+alertとする。部分返金はrefund IDで冪等化してpurchaseの返金累計へ反映する。内部保存失敗は5xxでStripe再送、保存済み後の処理失敗はinbox retry/alertであり200に偽装しない。

| Stripe event | metadata | 動作 |
|---|---|---|
| `checkout.session.completed` | `timelineCreditPurchaseId` | PAID化、期限設定、available加算、PURCHASE元帳 |
| `checkout.session.expired` | 同上 | PENDINGをCANCELLED |
| `payment_intent.payment_failed` | 同上 | PENDING/auto補充失敗、通知 |
| `charge.dispute.created` / `charge.dispute.funds_withdrawn` | `metadata.timelineCreditPurchaseId` 必須。無い場合だけ受信済み `payment_intent` / `charge` と自前purchase行の一意対応をinbox内で照合 | dispute作成、未使用credit凍結、used分recovery、送信・auto停止 |
| `charge.dispute.closed` / `charge.dispute.funds_reinstated` | 同上 | won/reinstatedなら解除、lostならSETTLEMENT_BLOCKED維持 |
| `refund.created` / `refund.updated` | purchase ID | 返金状態・liabilityを確定 |

イベント種別・metadata不整合は成功200で捨てず、監査記録とアラートを残して既存の再試行/デッドレター方針に従う。Stripe APIで返金する際は idempotency key を purchase / refund reason に束縛する。

## 5. エラーコード予約

`origin/main`（507264fb6）での `TIMELINE` 現在最大は `TIMELINE_017` である。したがって下表の `TIMELINE_018`〜`TIMELINE_027` を設計上予約する。実装開始時とマージ直前に全 `*ErrorCode.java` を再grepし、並行追加との衝突時は未マージ側が再採番する。クライアント起因はすべて`WARN`、外部Stripe/永続障害のみ`ERROR`とする。

| 予約コード | HTTP | Severity | 発生条件 |
|---|---:|---|---|
| TIMELINE_018 PAID_DELIVERY_CONFIRMATION_REQUIRED | 409 | WARN | 有料化したがtokenなし/期限切れ/見積不一致 |
| TIMELINE_019 PAID_DELIVERY_PERMISSION_DENIED | 403 | WARN | `SEND_PAID_TIMELINE`なし |
| TIMELINE_020 CREDIT_INSUFFICIENT | 409 | WARN | exact件数に対する予約不能。全rollback |
| TIMELINE_021 CREDIT_WALLET_FROZEN | 409 | WARN | dispute/closed/settlementで送信不可 |
| TIMELINE_022 AUTO_TOPUP_INVALID | 400 | WARN | 初回手動購入前・threshold/cap不正 |
| TIMELINE_023 PURCHASE_NOT_CANCELLABLE | 409 | WARN | 一部でも使用済み/返金済み |
| TIMELINE_024 DELIVERY_PROCESSING_LOCKED | 409 | WARN | PROCESSING/FAILURE_RESOLVING中の編集・削除 |
| TIMELINE_025 DELIVERY_RETRY_NOT_ALLOWED | 409 | WARN | 最終化済み/権限なし/attempt上限 |
| TIMELINE_026 DELIVERY_JOB_FAILED | 500 | ERROR | 最終ジョブ障害（API pollでは状態として200） |
| TIMELINE_027 CREDIT_STRIPE_FAILURE | 502 | ERROR | Checkout/Refund外部障害 |

HTTP statusの個別上書きは `GlobalExceptionHandler` に明示する。403/404のscope秘匿と409の状態・残高競合を混同しない。

第1精査により402は採らず、paid confirmation不足・残高不足・wallet freeze・policy/capはすべて409（状態/残高競合）に統一する。`TIMELINE_018`〜`TIMELINE_027`はこのHTTP契約に実装時更新し、origin/main最大+1・マージ時再確認の予約原則を維持する。
