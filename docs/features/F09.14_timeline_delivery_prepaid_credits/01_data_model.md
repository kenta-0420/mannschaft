# F09.14 データモデル

> **ステータス**: 🟢 設計完了（READMEを正本とする簡素化版）

## 1. 境界と関係

課金の集約は`scope_type/scope_id`単位で、TEAM財布とORGANIZATION財布を分離する。既存`timeline_posts`は参照するだけで、課金・配信の新規IDはUUIDv7とする。クロスドメインFKは作らず、scope/user/Stripeの外部IDと状態を保存する。

```text
scope ──< balance ──< purchase(lot) ──< ledger
   └──< delivery_job ──< recipient
                    └──< reservation_allocation >── purchase
```

## 2. 主要テーブル契約

### 財布・無料枠

`timeline_credit_balances(id, scope_type, scope_id, currency, available_credits, reserved_credits, frozen_credits, version, status, timezone, created_at, updated_at)`。`available/reserved/frozen >= 0`、currencyはJPY固定、wallet timezone初期値はAsia/Tokyo。auto-topupの設定・同意・月次counterは財布に重複保持しない。

`timeline_monthly_delivery_usages(id, scope_type, scope_id, period_start, free_post_count, version)` はscopeと財布timezoneの期間ごとに一行。`free_post_count <= 100`を無料判定の正本とし、月初は新期間行でリセットする。並行投稿は悲観ロックで一度だけ増やす。

### 購入lot

`timeline_credit_purchases(id, balance_id, scope_type, scope_id, kind, status, credits_purchased, remaining_credits, frozen_available_credits, amount_yen, stripe_checkout_session_id, stripe_payment_intent_id, stripe_charge_id NULL, mandate_id NULL, cap_period_start NULL, paid_at, expires_at, created_at, updated_at)`。

statusは必須CHECKで`PENDING|PAID|PARTIALLY_REFUNDED|REFUND_PENDING|REFUNDED|PAYMENT_FAILED|CANCEL_REQUESTED|CANCELLED|EXPIRED|DISPUTED`。`stripe_charge_id`、checkout/payment intent、refund/dispute外部IDはglobal UNIQUEとする。`remaining_credits`のasset集計対象は`status IN(PAID,PARTIALLY_REFUNDED,REFUND_PENDING,DISPUTED) AND remaining_credits>0`。消費可能FIFOはそのうち`status IN(PAID,PARTIALLY_REFUNDED,REFUND_PENDING)`かつwallet/lotが凍結されていないものだけで、DISPUTEDは予約不可の資産保持だけとする。`REFUNDED`はremaining=0かつ返金liability成功、partial成功後remaining>0はPARTIALLY_REFUNDEDである。

### 配信job・recipient

`timeline_delivery_jobs(id, post_id, scope_type, scope_id, created_by_user_id NULL, created_by_subject_hash NULL, created_by_hash_key_version NULL, created_period_start, billing_period_start NULL, status, billing_mode, estimated_recipient_count, exact_recipient_count NULL, publication_cut_as_of NULL, initial_reserved_credits, captured_credits, released_credits, expired_credits, remaining_reserved_credits, frozen_reserved_credits, dispute_lost_credits, failure_code NULL, paid_confirmation_id NULL, paid_consented_at NULL, created_at, updated_at)`。

statusは`PREPARING|AWAITING_TOPUP|PROCESSING|COMPLETED|FAILURE_RESOLVING|PUBLISH_BLOCKED|CANCEL_RESOLVING|DELETED`の必須CHECK。`exact_recipient_count`はmanifest確定前NULL、確定後は0を含む値。jobの会計恒等式は次の一つだけとする。

`initial_reserved = captured + released + expired + remaining_reserved + frozen_reserved + dispute_lost`。

`timeline_delivery_recipients(period_start, id, job_id, recipient_subject_hash NOT NULL, recipient_user_id NULL, status, delivered_at, failure_code)`。recipientは`USER`主体に固定し、`UNIQUE(period_start,job_id,recipient_subject_hash)`でuser_idが匿名化されても重複を防ぐ。personal feedはDELIVEREDだけ、scope feedはPUBLISHEDだけを表示する。

### reservation・ledger

`timeline_credit_reservation_allocations(id, job_id, purchase_id, initial_reserved_credits, captured_credits, released_credits, expired_credits, remaining_reserved_credits, frozen_reserved_credits, dispute_lost_credits, status)`。jobとallocationは同じ恒等式を持ち、job値はallocation合計。`available_lot = purchase.remaining_credits - Σ(allocation.remaining_reserved_credits + allocation.frozen_reserved_credits) - purchase.frozen_available_credits`。

`timeline_credit_ledger_entries(id, period_start, balance_id, purchase_id NULL, job_id NULL, allocation_id NULL, entry_type, total_delta, available_delta, reserved_delta, frozen_delta, idempotency_key, created_at)`。entryの意味は固定する。

| entry | total | available | reserved | frozen |
|---|---:|---:|---:|---:|
| PURCHASE | +n | +n | 0 | 0 |
| RESERVE | 0 | -n | +n | 0 |
| CAPTURE | -n | 0 | -n | 0 |
| RESERVATION_RELEASE / UNDELIVERABLE_RELEASE | 0 | +n | -n | 0 |
| DISPUTE_FREEZE / DISPUTE_FREEZE_RESERVED | 0 | -n | 0または-n | +n |
| DISPUTE_UNFREEZE | 0 | +n | 0または+n | -n |
| EXPIRE / EXPIRE_RESERVED / DISPUTE_LOSS / CUSTOMER_REFUND | -n | 該当bucket -n | 該当bucket -n | 該当bucket -n |

RESERVEはjob/allocationのinitialとremainingを同時に+nする。予約解放をREFUNDと呼ばない。顧客返金はcapture済みを対象にせず、remaining/frozen reservedを先に終端・release/unfreezeし、available化できたcreditだけをpurchase/balanceへ`CUSTOMER_REFUND`として記録する。返金liabilityはcredit asset外の別表である。

### auto-topup・確認・Stripe

`timeline_paid_delivery_mandates(id, scope_type, scope_id, consenting_user_id NULL, consenting_subject_hash NULL, consenting_hash_key_version NULL, customer_id, payment_method_id, enabled, threshold_credits, refill_credits, monthly_cap_credits, revoked_at NULL, created_at, updated_at)`。owner移譲・同意ADMIN離脱/剥奪・脱退でrevokeし、後任再同意までchargeしない。

`timeline_auto_topup_period_usages(mandate_id, period_start, used_credits, pending_credits, version, UNIQUE(mandate_id,period_start))`が月跨ぎcounterの正本。pendingは未収金で資産不変条件外だが、`pending_credits = Σ credits_purchased of linked AUTO_TOPUP purchases WHERE status IN(PENDING,CANCEL_REQUESTED)`を保つ。pending減算はPAID CAS、またはterminal `CANCELLED|PAYMENT_FAILED|EXPIRED`だけで行う。purchaseには`mandate_id`と`cap_period_start`を保存する。

`timeline_auto_topup_job_allocations(id,pending_purchase_id,mandate_id,balance_id,job_id,required_credits,allocated_pending_credits,status,created_at,updated_at)`、`UNIQUE(job_id)`、`CHECK(allocated_pending_credits<=required_credits)`でpendingの二重利用を防ぐ。

`timeline_delivery_confirmations`はtoken hash、scope、actor、必須`delivery_scope`、`consented_at`、expiresAt、consumedAtをappend-onlyで保存する。estimateの`estimated_recipient_count`、`estimated_charge_yen`、`as_of`はnullableの監査値として必要時だけconfirmationへ紐付け、実公開額の上限にはしない。`timeline_stripe_payment_objects(payment_intent_id UNIQUE,purchase_id,authoritative_status,last_event_created,credited_at,version)`はobject単位の正本とする。`timeline_stripe_webhook_inbox(event_id UNIQUE,event_type,payload,payload_hash,relay_status,attempt_count)`は署名検証後にclaimする。

### 主体・匿名化

actor/recipient/approver/consenting user等は、USERならnullable ID + `subject_hash BINARY(32)` + `hash_key_version`、SYSTEM/NONEなら全てNULLを許す`subject_kind` CHECKを各関連表へ適用する。recipientだけはUSER固定でhash NOT NULL。hashは高entropy per-user erasure tokenをHMACし、退会時にtoken対応表をcryptographic eraseする。active中は仮名化、erase後が匿名化であり、HMAC鍵保持期間と混同しない。

## 3. 保持・partition・移行

大容量append-onlyのrecipients/ledger/archiveだけを月次native partitionとし、PK/UNIQUEには`period_start`を含める。jobs/purchases/disputes/refund commands/liabilities/inbox/outbox/confirmations/mandates/period usagesは非partitionでglobal IDをUNIQUEにする。partition ledgerのglobal idempotencyは非partition claim表を先に取得する。raw recipient/ledgerは集計・reconciliation後13か月でcold archive exportしpartition DROP、無期限保存しない。

## 4. 実装時の技術検討事項

- lock順は必要な場合に限り`usage→balance→mandate→period usage→purchase→payment object→allocation→auto-topup job allocation→job→manifest/recipient`とする。inbox/object claim leaseとqueue leaseは短い独立Txで先にcommitし、会計Txでtokenを検証する。
- B1の不可視staging、publication cut、manifest hash、B2の短い確定Txは03の境界に従う。100万件の細粒度DDLやfencingの採用は実装前に性能検証する。
- Stripe全イベントmatrix、refund/dispute累積、payment_failed/canceledの詳細は02末尾に集約する。
