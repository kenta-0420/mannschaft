# F20.1 — 05 料金・契約センター／実決済運用

> **ステータス**: 🟢 設計完了（2026-08-31）
> **適用範囲**: Phase 2b の実決済、請求表示、支払方法、取消/撤回、Customer 所有を定義する。本書が 01〜04 の当該範囲を置換する。

## 1. 利用者への約束と境界

個人・チーム・組織（USER/TEAM/ORG）は各々が独立した課金所有者であり、操作した管理者個人の Stripe Customer、支払方法、請求履歴を共有しない。ログイン後の `/billing` は本人が管理できる全 scope を選択できる「料金・契約」ハブで、アクティブなチーム/組織の変更だけで別 scope の履歴を隠さない。所属又は管理権限を失ったときのみ一覧から除き、契約/履歴は保持して現管理者へ引き継ぐ。

通常の販売は月次自動更新のみであり、年額と一か月パスは販売しない。価格・BASIC の内容・Stripe Price は運用マスタが正本で、税込表示可能な有効 `billing_price_versions` が無い商品は公開・購入できない。税込を主表示し、税抜・税額・税名/税率を併記する。`is_included_in_price` は入力価格が税込か税抜かを解釈する属性であり、画面の主表示を切替える機能ではない。

対象外は F08.9 会費、F22.1 市決済、残高/プリペイドである。Stripe Tax `automatic_tax` は有効な登録を運用で確認した場合だけ使い、未登録時は運用マスタの税率で計算する。

## 2. 画面・導線・認可

| 画面 | 経路 | 内容 |
|---|---|---|
| 公開料金 | `/pricing` | 月額、初月日割り、自動更新、税込、解約方法を表示。未販売は「準備中」で CTA 無効 |
| 料金・契約 | `/billing?scopeKind={USER|TEAM|ORG}&scopeId={long}&tab={plan|invoices|payment|cancel}` | 現契約、次回請求、月別明細、支払方法、請求先、解約/撤回 |
| 互換導線 | `/settings/billing`、`/teams/{slug}/settings/billing`、`/organizations/{slug}/settings/billing`、`/billing/plans` | 削除せず、解決済み scope/tab を伴う `/billing` へ遷移 |

Checkout success/cancel と Portal return は `/billing` の許可済み path に固定し、scope/tab はサーバー署名済み state から復元する。任意 return URL は受け取らない。請求先メール/宛名は Checkout 前に確認する。TEAM/ORG での操作者メールは候補にすぎず、保存先は scope-owned Customer である。

消費者 API は **`BillingAccessGuard`** だけで認可する。SYSTEM_ADMIN の短絡許可はない。USER は本人、TEAM/ORG は ADMIN、又は当該scopeに一致する permission group からそれぞれ `MANAGE_TEAM_BILLING` / `MANAGE_ORGANIZATION_BILLING` を明示付与された DEPUTY_ADMIN に限定する。一般 MEMBER は既存 entitlements API の権利サマリだけを read でき、金額・請求先・invoice・Portal・変更・`/billing` summary は read できない。運営は別の `/api/v1/system-admin/billing/**` read-only API で、理由（1〜500文字）を必須にし `BILLING_OPERATOR_VIEWED` を監査する。運営 API は Portal、Checkout、変更、文書 URL を発行しない。

V196 Flyway は既存 Permission Catalog に大文字2キー **`MANAGE_TEAM_BILLING`（scope=TEAM）** と **`MANAGE_ORGANIZATION_BILLING`（scope=ORGANIZATION）** を登録し、ADMIN にだけ既定付与する。DEPUTY_ADMIN/MEMBER/SUPPORTER への `role_permissions` 行は作らない。DEPUTY_ADMIN には既存ロール権限管理 UI/API が permission group を通じ当該scopeのキーだけを明示付与/取消できる。`BillingAccessGuard` は毎要求、専用 `BillingAccessRepository.existsDeputyPermissionGroup(userId, scopeKind, scopeId, permission)`（`user_roles`、`user_permission_groups`、`permission_groups` の team_id/organization_id、`permission_group_permissions`、`permissions` を同一scopeで結合）を実行し、`RoleService.resolveEffectivePermissions` / `role_permissions` / cache を使わない。`GET /me/billing/scopes` も同Guardで USER + 管理可能 TEAM/ORG だけを返す。取消で403なら FE は保持済み金額・invoice・Contract state を直ちに破棄し、scopesを再取得して USER ハブへ遷移する。ロール変更・付与/取消は `BILLING_MANAGE_GRANTED/REVOKED` を監査する。

課金管理キーを含む permission group の作成/更新/削除/メンバー割当は、通常の `PermissionGroupService` を使わず `BillingPermissionGroupGuard` と専用Repository queryで**同一scopeのADMINだけ**に許可する。DEPUTY は作成者、変更者、削除者、割当者になれず、recipient は同一scopeの DEPUTY_ADMIN に限定し、actor=recipient の自己付与を拒否する。groupが課金キーを含むかは旧値・新値の両方で判定してキー削除による監査回避も禁止する。成功/拒否を `BILLING_PERMISSION_GROUP_CREATED/UPDATED/DELETED/ASSIGNED/DENIED` に actor、recipient、scope、permissionで監査する。

解約は一画面一確認で `「{終了日} まで利用できます。{翌月1日} 以降は請求されません。」` と明示する。理由、電話、複数確認、引き止めを置かない。月末前だけ `解約予定を取り消す` を表示する。

## 3. 暦月・Stripe の正準

JST (`Asia/Tokyo`) の `[月初00:00, 翌月初00:00)` を課金期間とし、DB は既存規約どおり JST `DATETIME(6)`、Stripe へは UTC epoch seconds を送る。

| 操作 | 請求・権利 | Stripe 処理 |
|---|---|---|
| 新規 | 契約時から当月末まで日割り。翌月以降満額 | Checkout subscription、翌月1日 anchor、`create_prorations`。`invoice.paid` まで ACTIVE/権利発行しない |
| upgrade | 即時の差額日割り | preview 後、`always_invoice` + `pending_if_incomplete`。`invoice.paid` で確定、失敗なら旧プラン維持 |
| downgrade | 翌月1日からのみ | **Stripe Subscription Schedule に一本化**。当月の価格/権利を即時変更しない |
| 解約/撤回 | 当月末終了/当月末まで撤回可 | `cancel_at_period_end=true/false` |

Checkout 作成時は `session.metadata` **と** `subscription_data.metadata` の両方に `billingContractId`、`scopeKind`、`scopeId`、`billingCustomerId` を書く。これにより `invoice.paid` が `checkout.session.completed` より先に到着しても subscription metadata から scope-owned contract を解決できる。metadata 値は UUID/enum/int64 のみで PII を入れない。

quote は10分だけ有効な確定前見積りで、Checkout Sessionとは別物である。Session の `expiresAt=min(now+23h59m,nextMonthStart-60秒)` とし、Stripe server/API遅延の60秒安全marginを確保する。残り期間が30分+60秒未満なら作成せず `ENTITLEMENT_022`(409) と `availableAt`（翌月1日00:00 JST）、`reason=MONTH_BOUNDARY` を返す。Checkout直前に価格版・人数・税・period・日割り基準日を再計算し、quote保存値と差異があればquoteを消費せず `ENTITLEMENT_023`(409, reason=QUOTE_STALE)で新quoteを要求する。UI は「月初に最新金額を再見積りしてください」と表示する。

競合は contract version と idempotency により直列化する。upgrade `PENDING_PAYMENT` 中は解約/downgrade/別 change を409、解約予約中は撤回まで change を409、downgrade予約中の解約は schedule を取消してから解約予約を作る。subscription/customer を Update API で付替えない。

## 4. 状態・Webhook

```
PENDING --invoice.paid--> ACTIVE
PENDING --checkout.session.expired--> CANCELLED
ACTIVE --upgrade requested--> ACTIVE + change:PENDING_PAYMENT
change:PENDING_PAYMENT --payment_action_required--> REQUIRES_ACTION --invoice.paid--> APPLIED（新権利を原子的に発行）
change:REQUIRES_ACTION --payment_intent.requires_action/invoice.payment_failed（pending_update存続）--> REQUIRES_ACTION
change:PENDING_PAYMENT/REQUIRES_ACTION --definitive_decline/invoice.voided/customer.subscription.pending_update_expired--> FAILED（旧権利維持）
ACTIVE --downgrade requested--> ACTIVE + change:CREATING_SCHEDULE --schedule created--> SCHEDULED
change:SCHEDULED --customer.subscription.updated(target price + schedule phase + effectiveAt)--> APPLIED
ACTIVE --cancel_at_period_end--> ACTIVE(cancel scheduled)
ACTIVE(cancel scheduled) --resume--> ACTIVE
ACTIVE(cancel scheduled) --customer.subscription.deleted--> EXPIRED
PAST_DUE --invoice.paid--> ACTIVE
```

既存 endpoint **`POST /api/v1/webhooks/stripe`** と既存 `stripe_webhook_events` を再利用する。raw body は署名検証中のメモリだけで扱い、検証後に必要な Stripe object を取得する。DBには event id/type/object ref/payload hash/処理状態だけを残し、raw payload を永続化しない。監査ログにも payload/PIIを複写しない。

| Stripe event | 所有判定・照合 | 副作用 |
|---|---|---|
| `checkout.session.completed` | session metadata の contractId/scope/customerId と session.customer/subscription を `billing_customers`/contract に照合 | Customer/Subscription ref を結ぶのみ。ACTIVE 化しない |
| `checkout.session.expired` | PENDING contract + 同一 session | PENDING→CANCELLED、pointer 解放 |
| `invoice.finalized/paid/payment_failed/voided` | invoice.subscription metadata（session completed 未達でも可）→contract、invoice.customer→scope Customer、Stripe 最新 invoice を再取得 | invoice/line 投影。**paid のみ** PENDING/upgrade を確定 |
| `invoice.payment_action_required` | 最新Invoice/PaymentIntent、pending update、contract/customerを二重照合 | 当該upgradeをREQUIRES_ACTIONにする。`invoice.payment_failed`でもPIがrequires_action又はpending update存続なら同じ状態を維持し、decline/void/expiredだけFAILED |
| `customer.subscription.updated` | subscription ref、customer ref、最新 Subscription を再取得 | anchor、cancel、schedule、period を単調に同期。downgrade は target Stripe Price・schedule phase・effectiveAt 到達が全て一致した場合だけ APPLIED |
| `customer.subscription.pending_update_applied` | `stripe_invoice_ref` + `stripe_subscription_ref` + target snapshot が一致する PENDING_PAYMENT change | `invoice.paid` 済みであることを再確認して upgrade を APPLIED |
| `customer.subscription.pending_update_expired` | 同じ3要素で PENDING_PAYMENT change を解決 | change を FAILED、旧プラン/権利を維持 |
| `customer.subscription.deleted` | contract の最新 object と period end | 有償期末解約を EXPIRED、pointer削除、権利失効 |
| `subscription_schedule.updated/released/canceled/completed` | change.schedule ref、customer/subscription/scope | schedule の補助同期、失敗/終了又は migration saga を遷移。無期限downgradeの APPLIED 判定には使わない |

`pending_update` は Stripe の独立 ID ではないため、`stripe_invoice_ref` + `stripe_subscription_ref` + `pending_update_expires_at` + `pending_update_target_snapshot` で変更多重度を特定する。event の `created` と取得した Stripe object の更新時刻/状態を比較し、古い event で現状態を戻さない。**renewal invoice**はsubscription metadataで通常契約を解決し、`invoice.payment_failed`でACTIVE→PAST_DUE（`valid_until`とcurrent periodを延長しない）、retryの`invoice.paid`でのみPAST_DUE→ACTIVEかつ次period/権利を延長する。期末まで未払はStripeの最終状態と最新periodを照合してEXPIREDにする。upgrade change invoiceは`billing_contract_changes.stripe_invoice_ref`を持つものだけで、renewal遷移を流用しない。`charge.refunded`/`credit_note.*`/`charge.dispute.*` は必ず受信し、`billing_invoice_adjustments`へPSP object ref UNIQUEの`REFUND`/`CREDIT_NOTE`/`DISPUTE`複数行として投影してユーザー明細の`adjustments[]`へ返す。返金・credit noteは過去の正当な権利を原則遡及撤回せず、二重請求は同一invoice/line/Price照合後に返金Sagaとincidentを作成する。dispute中は新しい有償変更を止め、勝訴なら復帰、敗訴又は回収不能は期末でEXPIREDにする。処理失敗は既存 event 表の `attempt_count/failed_at/processed_at` を更新して指数 backoff、上限後は照合キューへ送る。重複 event は副作用なしで 200、署名不正は 400、内部失敗は 5xx として Stripe 再送を受ける。

Webhook controller 契約は厳格に分ける。署名不正は400、billing所有と確定したイベントの一時DB/Stripe失敗は必ず `StripeWebhookRetryableException` に包んで5xx（Stripe再送対象）、所有外だけが既存F08.9へ**event idを消費せず**fallthroughし、最終的に200にする。invoiceのsubscription ref逆引きmiss時はStripe Subscriptionを取得し、subscription metadata のcontractId/scope/customerIdをDBのscope-owned Customerと厳密照合してからrefをbindしbilling処理する。照合不能なものだけmembershipへfallthroughする。実 `StripeWebhookController` の署名fixtureで400/5xx/fallthrough200とevent id未消費を契約テストする。

返金・credit note・disputeはinvoice lifecycleと混ぜない。`billing_invoices`はStripe invoiceのOPEN/PAID/VOID等の集約投影だけを単調更新し、`billing_invoice_adjustments`は`REFUND`/`CREDIT_NOTE`/`DISPUTE`をPSP object ref UNIQUEの不変複数行として保存する。`refund.*`/`charge.refunded`はownershipをinvoice/customer/contract/Stripe objectで二重照合し、同一objectの重複・順不同は無副作用、FAILED→SUCCEEDEDだけを許す。二重請求を検知した返金は専用`billing_contract_operations(kind=REFUND)`を作り、Stripe返金の結果をadjustmentへ投影する。部分返金の複数回、credit note、dispute OPEN→WON/LOST/CLOSED、Stripe/DB失敗と再送をAC化し、DTOは`adjustments:[{kind,amount,status,effectiveAt,reason?}]`をinvoice detailへ追加する。

## 5. 完全 DDL と Flyway

全新規業務表は UUIDv7 `BINARY(16)`、`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`。scope/organization はクロスドメイン論理参照、同一 billing domain の親子には FK を張る。`organization_id`、`version`、`deleted_at`、検索 index を明記する。実装 migration は **`V196.20260831000000__expand_billing_center.sql`** を仮番とし、マージ直前に Flyway 最大値と衝突しない番号へ再採番する。

```sql
CREATE TABLE billing_customers (
 id BINARY(16) NOT NULL COMMENT 'UUIDv7', scope_kind VARCHAR(8) NOT NULL, scope_id BIGINT UNSIGNED NOT NULL,
 organization_id BIGINT UNSIGNED NULL, psp_customer_ref VARCHAR(255) NULL, billing_email VARCHAR(254) NULL,
 billing_name VARCHAR(255) NULL, status VARCHAR(32) NOT NULL DEFAULT 'PROVISIONING', provision_attempts INT NOT NULL DEFAULT 0, last_provision_error_code VARCHAR(64) NULL, version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), deleted_at DATETIME(6) NULL,
 PRIMARY KEY (id), UNIQUE KEY uk_bcu_scope (scope_kind,scope_id), UNIQUE KEY uk_bcu_psp (psp_customer_ref), KEY idx_bcu_org (organization_id),
 CONSTRAINT chk_bcu_scope CHECK (scope_kind IN ('USER','TEAM','ORG')),
 CONSTRAINT chk_bcu_status CHECK (status IN ('PROVISIONING','ACTIVE','PROVISION_FAILED','MIGRATION_REQUIRED','CLOSED')),
 CONSTRAINT chk_bcu_ref_by_status CHECK ((status='ACTIVE' AND psp_customer_ref IS NOT NULL) OR (status IN ('PROVISIONING','PROVISION_FAILED') AND psp_customer_ref IS NULL) OR status IN ('MIGRATION_REQUIRED','CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='scope所有Stripe Customer';

CREATE TABLE billing_price_versions (
 -- catalog revision only: Money/tax/Stripe Price are stored exclusively in billing_price_band_versions
 id BINARY(16) NOT NULL COMMENT 'UUIDv7', product_kind VARCHAR(8) NOT NULL, product_key VARCHAR(64) NOT NULL, scope_kind VARCHAR(8) NOT NULL,
 organization_id BIGINT UNSIGNED NULL, catalog_revision VARCHAR(64) NOT NULL COMMENT '不変の運用識別子', revision_no BIGINT UNSIGNED NOT NULL COMMENT 'product/scopeごとの不変連番', status VARCHAR(24) NOT NULL DEFAULT 'DRAFT', provision_attempts INT UNSIGNED NOT NULL DEFAULT 0, last_provision_error_code VARCHAR(64) NULL,
 effective_from DATETIME(6) NOT NULL,
 effective_until DATETIME(6) NULL, lock_version BIGINT NOT NULL DEFAULT 0 COMMENT '可変操作CAS専用', created_by BIGINT UNSIGNED NULL, creation_source VARCHAR(24) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), deleted_at DATETIME(6) NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_bpv_identity(id,product_kind,product_key,scope_kind), UNIQUE KEY uk_bpv_revision_no(product_kind,product_key,scope_kind,revision_no), UNIQUE KEY uk_bpv_catalog_revision(product_kind,product_key,scope_kind,catalog_revision), KEY idx_bpv_catalog(product_kind,product_key,scope_kind,effective_from,effective_until), KEY idx_bpv_org(organization_id),
 CONSTRAINT chk_bpv_kind CHECK(product_kind IN ('PLAN','ADDON')), CONSTRAINT chk_bpv_scope CHECK(scope_kind IN ('USER','TEAM','ORG')),
 CONSTRAINT chk_bpv_status CHECK(status IN ('DRAFT','PROVISIONING','PROVISION_FAILED','READY','SCHEDULED','ACTIVE','RETIRED')), CONSTRAINT chk_bpv_source CHECK((creation_source='OPERATOR' AND created_by IS NOT NULL) OR creation_source='SYSTEM_BACKFILL')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='不変の販売価格/Stripe Price正本';

CREATE TABLE billing_price_band_versions (
 id BINARY(16) NOT NULL COMMENT 'UUIDv7', product_kind VARCHAR(8) NOT NULL, product_key VARCHAR(64) NOT NULL, scope_kind VARCHAR(8) NOT NULL, band_no INT UNSIGNED NOT NULL,
 min_members INT UNSIGNED NOT NULL, max_members INT UNSIGNED NULL, price_version_id BINARY(16) NOT NULL, stripe_price_ref VARCHAR(255) NULL, currency CHAR(3) NOT NULL DEFAULT 'JPY', input_amount BIGINT NOT NULL, tax_behavior VARCHAR(16) NOT NULL, tax_code_snapshot VARCHAR(64) NOT NULL, tax_master_snapshot JSON NOT NULL, amount_excluding_tax BIGINT NOT NULL, tax_amount BIGINT NOT NULL, tax_rate_basis_points INT NOT NULL, tax_name_snapshot VARCHAR(64) NOT NULL, is_included_in_price BOOLEAN NOT NULL, amount_including_tax BIGINT NOT NULL,
 effective_from DATETIME(6) NOT NULL, effective_until DATETIME(6) NULL, status VARCHAR(24) NOT NULL DEFAULT 'DRAFT', provision_error_code VARCHAR(64) NULL, lock_version BIGINT NOT NULL DEFAULT 0, created_by BIGINT UNSIGNED NULL, creation_source VARCHAR(24) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), deleted_at DATETIME(6) NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_bpbv_stripe_price(stripe_price_ref), UNIQUE KEY uk_bpbv_revision_band(price_version_id,band_no), KEY idx_bpbv_select(product_kind,product_key,scope_kind,status,effective_from,effective_until,min_members,max_members),
 CONSTRAINT fk_bpbv_price_identity FOREIGN KEY(price_version_id,product_kind,product_key,scope_kind) REFERENCES billing_price_versions(id,product_kind,product_key,scope_kind), CONSTRAINT chk_bpbv_kind CHECK(product_kind IN ('PLAN','ADDON')), CONSTRAINT chk_bpbv_scope CHECK(scope_kind IN ('USER','TEAM','ORG')), CONSTRAINT chk_bpbv_currency CHECK(currency='JPY'), CONSTRAINT chk_bpbv_tax CHECK(tax_rate_basis_points BETWEEN 0 AND 10000), CONSTRAINT chk_bpbv_amount CHECK(input_amount>=0 AND amount_excluding_tax>=0 AND tax_amount>=0 AND amount_including_tax>=0), CONSTRAINT chk_bpbv_behavior CHECK(tax_behavior IN ('INCLUSIVE','EXCLUSIVE') AND is_included_in_price=(tax_behavior='INCLUSIVE')), CONSTRAINT chk_bpbv_range CHECK(max_members IS NULL OR max_members>=min_members), CONSTRAINT chk_bpbv_active CHECK((status IN ('DRAFT','PROVISIONING','PROVISION_FAILED') AND stripe_price_ref IS NULL) OR (status IN ('READY','SCHEDULED','ACTIVE','APPLIED','RETIRED') AND stripe_price_ref IS NOT NULL)), CONSTRAINT chk_bpbv_source CHECK((creation_source='OPERATOR' AND created_by IS NOT NULL) OR creation_source='SYSTEM_BACKFILL')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='人数バンド不変価格版';

CREATE TABLE billing_quotes (
 id BINARY(16) NOT NULL COMMENT 'UUIDv7', actor_id BIGINT UNSIGNED NOT NULL, billing_customer_id BINARY(16) NOT NULL, organization_id BIGINT UNSIGNED NULL,
 scope_kind VARCHAR(8) NOT NULL, scope_id BIGINT UNSIGNED NOT NULL, product_kind VARCHAR(8) NOT NULL, product_key VARCHAR(64) NOT NULL, price_band_version_id BINARY(16) NOT NULL,
 member_count INT UNSIGNED NULL, tax_snapshot JSON NOT NULL, amount_snapshot JSON NOT NULL, period_start DATETIME(6) NOT NULL, period_end DATETIME(6) NOT NULL, proration_at DATETIME(6) NOT NULL, contract_version BIGINT NULL,
 request_hash CHAR(64) NOT NULL, expires_at DATETIME(6) NOT NULL, consumed_at DATETIME(6) NULL, version BIGINT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), deleted_at DATETIME(6) NULL,
 PRIMARY KEY(id), KEY idx_bq_actor_expiry(actor_id,expires_at), KEY idx_bq_scope(scope_kind,scope_id,expires_at), KEY idx_bq_org(organization_id),
 CONSTRAINT fk_bq_customer FOREIGN KEY(billing_customer_id) REFERENCES billing_customers(id), CONSTRAINT fk_bq_price_band FOREIGN KEY(price_band_version_id) REFERENCES billing_price_band_versions(id),
 CONSTRAINT chk_bq_kind CHECK(product_kind IN ('PLAN','ADDON')), CONSTRAINT chk_bq_scope CHECK(scope_kind IN ('USER','TEAM','ORG'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Checkout直前再照合する10分見積り';

CREATE TABLE billing_change_previews (
 id BINARY(16) NOT NULL COMMENT 'UUIDv7', actor_id BIGINT UNSIGNED NOT NULL, contract_id BINARY(16) NOT NULL, billing_customer_id BINARY(16) NOT NULL, organization_id BIGINT UNSIGNED NULL,
 scope_kind VARCHAR(8) NOT NULL, scope_id BIGINT UNSIGNED NOT NULL, product_kind VARCHAR(8) NOT NULL DEFAULT 'PLAN', product_key VARCHAR(64) NOT NULL, from_price_band_version_id BINARY(16) NOT NULL, to_price_band_version_id BINARY(16) NOT NULL, member_count INT UNSIGNED NULL,
 tax_snapshot JSON NOT NULL, amount_snapshot JSON NOT NULL, period_start DATETIME(6) NOT NULL, period_end DATETIME(6) NOT NULL, proration_at DATETIME(6) NOT NULL, contract_version BIGINT NOT NULL, request_hash CHAR(64) NOT NULL,
 expires_at DATETIME(6) NOT NULL, consumed_at DATETIME(6) NULL, version BIGINT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), deleted_at DATETIME(6) NULL,
 PRIMARY KEY(id), KEY idx_bcp_actor_contract_expiry(actor_id,contract_id,expires_at), KEY idx_bcp_org(organization_id),
 CONSTRAINT fk_bcp_contract FOREIGN KEY(contract_id) REFERENCES billing_contracts(id), CONSTRAINT fk_bcp_customer FOREIGN KEY(billing_customer_id) REFERENCES billing_customers(id),
 CONSTRAINT fk_bcp_from FOREIGN KEY(from_price_band_version_id) REFERENCES billing_price_band_versions(id), CONSTRAINT fk_bcp_to FOREIGN KEY(to_price_band_version_id) REFERENCES billing_price_band_versions(id), CONSTRAINT chk_bcp_scope CHECK(scope_kind IN ('USER','TEAM','ORG'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='一回消費の変更preview';

CREATE TABLE billing_contract_changes (
 id BINARY(16) NOT NULL COMMENT 'UUIDv7', contract_id BINARY(16) NOT NULL, billing_customer_id BINARY(16) NOT NULL,
 organization_id BIGINT UNSIGNED NULL, kind VARCHAR(16) NOT NULL, status VARCHAR(24) NOT NULL, from_plan_key VARCHAR(64) NOT NULL, to_plan_key VARCHAR(64) NOT NULL,
 from_price_band_version_id BINARY(16) NOT NULL, to_price_band_version_id BINARY(16) NOT NULL, from_amount_including_tax BIGINT NOT NULL, to_amount_including_tax BIGINT NOT NULL,
 stripe_invoice_ref VARCHAR(255) NULL, stripe_subscription_ref VARCHAR(255) NULL, pending_update_expires_at DATETIME(6) NULL, pending_update_target_snapshot JSON NULL, stripe_schedule_ref VARCHAR(255) NULL,
 effective_at DATETIME(6) NOT NULL, expires_at DATETIME(6) NULL, idempotency_key CHAR(36) NOT NULL, request_hash CHAR(64) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 created_by BIGINT UNSIGNED NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), deleted_at DATETIME(6) NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_bcc_idempotency(contract_id,idempotency_key), UNIQUE KEY uk_bcc_invoice(stripe_invoice_ref), UNIQUE KEY uk_bcc_schedule(stripe_schedule_ref),
 KEY idx_bcc_contract_status(contract_id,status,effective_at), KEY idx_bcc_org(organization_id),
 CONSTRAINT fk_bcc_contract FOREIGN KEY(contract_id) REFERENCES billing_contracts(id), CONSTRAINT fk_bcc_customer FOREIGN KEY(billing_customer_id) REFERENCES billing_customers(id),
 CONSTRAINT fk_bcc_from_price_band FOREIGN KEY(from_price_band_version_id) REFERENCES billing_price_band_versions(id), CONSTRAINT fk_bcc_to_price_band FOREIGN KEY(to_price_band_version_id) REFERENCES billing_price_band_versions(id),
 CONSTRAINT chk_bcc_kind CHECK(kind IN ('UPGRADE','DOWNGRADE')), CONSTRAINT chk_bcc_status CHECK(status IN ('PENDING_PAYMENT','REQUIRES_ACTION','CREATING_SCHEDULE','SCHEDULED','APPLIED','FAILED','CANCELLED')),
 CONSTRAINT chk_bcc_refs CHECK((kind='UPGRADE' AND stripe_schedule_ref IS NULL) OR (kind='DOWNGRADE' AND status='CREATING_SCHEDULE' AND stripe_schedule_ref IS NULL) OR (kind='DOWNGRADE' AND status IN ('SCHEDULED','APPLIED') AND stripe_schedule_ref IS NOT NULL) OR (kind='DOWNGRADE' AND status IN ('FAILED','CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='プラン変更Saga';

CREATE TABLE billing_contract_operations (
 id BINARY(16) NOT NULL COMMENT 'UUIDv7', contract_id BINARY(16) NOT NULL, billing_customer_id BINARY(16) NOT NULL, organization_id BIGINT UNSIGNED NULL, kind VARCHAR(24) NOT NULL, status VARCHAR(24) NOT NULL, step VARCHAR(32) NOT NULL,
 idempotency_key CHAR(36) NOT NULL, request_hash CHAR(64) NOT NULL, stripe_subscription_ref VARCHAR(255) NULL, stripe_schedule_ref VARCHAR(255) NULL, effective_at DATETIME(6) NULL, error_code VARCHAR(64) NULL, version BIGINT NOT NULL DEFAULT 0, actor_kind VARCHAR(8) NOT NULL, created_by BIGINT UNSIGNED NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), deleted_at DATETIME(6) NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_bco_idempotency(contract_id,idempotency_key), KEY idx_bco_contract_status(contract_id,status), KEY idx_bco_org(organization_id),
 CONSTRAINT fk_bco_contract FOREIGN KEY(contract_id) REFERENCES billing_contracts(id), CONSTRAINT fk_bco_customer FOREIGN KEY(billing_customer_id) REFERENCES billing_customers(id),
 CONSTRAINT chk_bco_kind CHECK(kind IN ('PLAN_CHANGE','CANCEL','RESUME','DOWNGRADE_TO_CANCEL','MIGRATION','MEMBER_REPRICE','REFUND')), CONSTRAINT chk_bco_status CHECK(status IN ('CREATED','CALLING_STRIPE','APPLIED','FAILED','RECONCILIATION_REQUIRED','CANCELLED')),
 CONSTRAINT chk_bco_actor CHECK((actor_kind='USER' AND created_by IS NOT NULL) OR (actor_kind='SYSTEM' AND created_by IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='cancel/resumeとschedule競合Saga';

ALTER TABLE billing_contract_changes ADD COLUMN operation_id BINARY(16) NOT NULL AFTER id,
 ADD UNIQUE KEY uk_bcc_operation(operation_id),
 ADD CONSTRAINT fk_bcc_operation FOREIGN KEY(operation_id) REFERENCES billing_contract_operations(id);

CREATE TABLE active_billing_contract_operation_pointers (
 contract_id BINARY(16) NOT NULL, operation_id BINARY(16) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 PRIMARY KEY(contract_id), UNIQUE KEY uk_abcop_operation(operation_id), CONSTRAINT fk_abcop_contract FOREIGN KEY(contract_id) REFERENCES billing_contracts(id), CONSTRAINT fk_abcop_operation FOREIGN KEY(operation_id) REFERENCES billing_contract_operations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='同時cancel/resume操作ポインタ';

CREATE TABLE billing_membership_price_adjustments (
 id BINARY(16) NOT NULL COMMENT 'UUIDv7', operation_id BINARY(16) NOT NULL, contract_id BINARY(16) NOT NULL, organization_id BIGINT UNSIGNED NULL,
 period_start DATETIME(6) NOT NULL, member_count_snapshot INT UNSIGNED NOT NULL, from_price_band_version_id BINARY(16) NOT NULL, to_price_band_version_id BINARY(16) NOT NULL,
 stripe_schedule_ref VARCHAR(255) NULL, status VARCHAR(24) NOT NULL, idempotency_key CHAR(36) NOT NULL, version BIGINT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), deleted_at DATETIME(6) NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_bmpa_operation(operation_id), UNIQUE KEY uk_bmpa_contract_period(contract_id,period_start), UNIQUE KEY uk_bmpa_schedule(stripe_schedule_ref), KEY idx_bmpa_org(organization_id), KEY idx_bmpa_status(status,period_start),
 CONSTRAINT fk_bmpa_operation FOREIGN KEY(operation_id) REFERENCES billing_contract_operations(id), CONSTRAINT fk_bmpa_contract FOREIGN KEY(contract_id) REFERENCES billing_contracts(id), CONSTRAINT fk_bmpa_from_band FOREIGN KEY(from_price_band_version_id) REFERENCES billing_price_band_versions(id), CONSTRAINT fk_bmpa_to_band FOREIGN KEY(to_price_band_version_id) REFERENCES billing_price_band_versions(id), CONSTRAINT chk_bmpa_status CHECK(status IN ('CREATED','SCHEDULED','APPLIED','FAILED','RECONCILIATION_REQUIRED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='次暦月の人数band価格変更Saga';

CREATE TABLE billing_customer_migrations (
 id BINARY(16) NOT NULL COMMENT 'UUIDv7', operation_id BINARY(16) NOT NULL, contract_id BINARY(16) NOT NULL, billing_customer_id BINARY(16) NOT NULL,
 organization_id BIGINT UNSIGNED NULL, legacy_psp_customer_ref VARCHAR(255) NOT NULL, legacy_psp_subscription_ref VARCHAR(255) NOT NULL,
 stripe_setup_intent_ref VARCHAR(255) NULL, setup_intent_expires_at DATETIME(6) NULL, default_payment_method_ref VARCHAR(255) NULL, stripe_schedule_ref VARCHAR(255) NULL, schedule_metadata_hash CHAR(64) NULL, effective_at DATETIME(6) NOT NULL, status VARCHAR(32) NOT NULL,
 compensation_reason VARCHAR(500) NULL, idempotency_key CHAR(36) NOT NULL, version BIGINT NOT NULL DEFAULT 0, created_by BIGINT UNSIGNED NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), deleted_at DATETIME(6) NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_bcm_operation(operation_id), UNIQUE KEY uk_bcm_contract(contract_id), UNIQUE KEY uk_bcm_setup(stripe_setup_intent_ref), UNIQUE KEY uk_bcm_schedule(stripe_schedule_ref), UNIQUE KEY uk_bcm_idempotency(contract_id,idempotency_key), KEY idx_bcm_status(status,effective_at), KEY idx_bcm_org(organization_id),
 CONSTRAINT fk_bcm_operation FOREIGN KEY(operation_id) REFERENCES billing_contract_operations(id), CONSTRAINT fk_bcm_contract FOREIGN KEY(contract_id) REFERENCES billing_contracts(id), CONSTRAINT fk_bcm_customer FOREIGN KEY(billing_customer_id) REFERENCES billing_customers(id),
 CONSTRAINT chk_bcm_status CHECK(status IN ('CREATED','SETUP_INTENT_CREATED','PAYMENT_METHOD_COLLECTED','SCHEDULE_CREATED','OLD_CANCEL_SCHEDULED','COMPLETED','NEW_PAYMENT_PAST_DUE','COMPENSATING','COMPENSATED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='legacy Customer移行Saga';

CREATE TABLE billing_invoices (
 id BINARY(16) NOT NULL COMMENT 'UUIDv7', billing_customer_id BINARY(16) NOT NULL, contract_id BINARY(16) NULL, organization_id BIGINT UNSIGNED NULL,
 scope_kind VARCHAR(8) NOT NULL, scope_id BIGINT UNSIGNED NOT NULL, psp_invoice_ref VARCHAR(255) NOT NULL, psp_subscription_ref VARCHAR(255) NULL,
 billing_reason VARCHAR(32) NOT NULL, status VARCHAR(16) NOT NULL, period_start DATETIME(6) NULL, period_end DATETIME(6) NULL, currency CHAR(3) NOT NULL DEFAULT 'JPY',
 subtotal_amount BIGINT NOT NULL, discount_amount BIGINT NOT NULL DEFAULT 0, tax_amount BIGINT NOT NULL DEFAULT 0, total_amount BIGINT NOT NULL,
 issuer_name_snapshot VARCHAR(255) NOT NULL, billing_name_snapshot VARCHAR(255) NULL, billing_email_snapshot VARCHAR(254) NULL, billing_address_snapshot JSON NULL,
 finalized_at DATETIME(6) NULL, paid_at DATETIME(6) NULL, voided_at DATETIME(6) NULL, version BIGINT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), deleted_at DATETIME(6) NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_bi_psp(psp_invoice_ref), KEY idx_bi_scope_period(scope_kind,scope_id,period_end), KEY idx_bi_customer_period(billing_customer_id,period_end), KEY idx_bi_org(organization_id),
 CONSTRAINT fk_bi_customer FOREIGN KEY(billing_customer_id) REFERENCES billing_customers(id), CONSTRAINT fk_bi_contract FOREIGN KEY(contract_id) REFERENCES billing_contracts(id),
 CONSTRAINT chk_bi_scope CHECK(scope_kind IN ('USER','TEAM','ORG')), CONSTRAINT chk_bi_status CHECK(status IN ('DRAFT','OPEN','PAID','UNCOLLECTIBLE','VOID'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Stripe invoice不変投影';

CREATE TABLE billing_invoice_adjustments (
 id BINARY(16) NOT NULL COMMENT 'UUIDv7', invoice_id BINARY(16) NOT NULL, operation_id BINARY(16) NULL, organization_id BIGINT UNSIGNED NULL, kind VARCHAR(16) NOT NULL, psp_object_ref VARCHAR(255) NOT NULL,
 amount BIGINT NOT NULL, currency CHAR(3) NOT NULL DEFAULT 'JPY', status VARCHAR(24) NOT NULL, reason VARCHAR(128) NULL, effective_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), deleted_at DATETIME(6) NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_bia_object(psp_object_ref), KEY idx_bia_invoice_kind(invoice_id,kind,effective_at), KEY idx_bia_operation(operation_id), KEY idx_bia_org(organization_id),
 CONSTRAINT fk_bia_invoice FOREIGN KEY(invoice_id) REFERENCES billing_invoices(id), CONSTRAINT fk_bia_operation FOREIGN KEY(operation_id) REFERENCES billing_contract_operations(id), CONSTRAINT chk_bia_kind CHECK(kind IN ('REFUND','CREDIT_NOTE','DISPUTE')), CONSTRAINT chk_bia_currency CHECK(currency='JPY'), CONSTRAINT chk_bia_amount CHECK(amount>=0), CONSTRAINT chk_bia_status CHECK(status IN ('PENDING','SUCCEEDED','FAILED','OPEN','WON','LOST','CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='返金・credit note・disputeの不変複数行投影';

CREATE TABLE billing_invoice_lines (
 id BINARY(16) NOT NULL COMMENT 'UUIDv7', invoice_id BINARY(16) NOT NULL, organization_id BIGINT UNSIGNED NULL, price_band_version_id BINARY(16) NULL, stripe_price_ref VARCHAR(255) NULL, psp_line_ref VARCHAR(255) NOT NULL, description_snapshot VARCHAR(500) NOT NULL,
 quantity DECIMAL(12,3) NOT NULL DEFAULT 1, amount_excluding_tax BIGINT NOT NULL, discount_amount BIGINT NOT NULL DEFAULT 0, tax_name_snapshot VARCHAR(64) NULL, tax_rate_basis_points INT NULL,
 tax_amount BIGINT NOT NULL DEFAULT 0, is_included_in_price BOOLEAN NOT NULL, amount_including_tax BIGINT NOT NULL, period_start DATETIME(6) NULL, period_end DATETIME(6) NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id), UNIQUE KEY uk_bil_line(invoice_id,psp_line_ref), KEY idx_bil_org(organization_id), KEY idx_bil_band(price_band_version_id), CONSTRAINT fk_bil_invoice FOREIGN KEY(invoice_id) REFERENCES billing_invoices(id), CONSTRAINT fk_bil_band FOREIGN KEY(price_band_version_id) REFERENCES billing_price_band_versions(id), CONSTRAINT chk_bil_quantity CHECK(quantity>0), CONSTRAINT chk_bil_tax CHECK(tax_rate_basis_points IS NULL OR tax_rate_basis_points BETWEEN 0 AND 10000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='請求明細不変投影';

ALTER TABLE billing_contracts MODIFY COLUMN psp_customer_ref VARCHAR(255) NULL COMMENT 'Stripe Customer ID（履歴参照）',
 MODIFY COLUMN psp_subscription_ref VARCHAR(255) NULL COMMENT 'Stripe Subscription ID（webhook逆引き）',
 ADD COLUMN billing_customer_id BINARY(16) NULL AFTER psp_customer_ref,
 ADD COLUMN price_band_version_id BINARY(16) NULL AFTER price_jpy_snapshot, ADD COLUMN billing_cycle_anchor_at DATETIME(6) NULL,
 ADD COLUMN cancel_scheduled_at DATETIME(6) NULL, ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
 ADD KEY idx_bc_customer(billing_customer_id), ADD KEY idx_bc_price_band(price_band_version_id),
 ADD CONSTRAINT fk_bc_customer FOREIGN KEY(billing_customer_id) REFERENCES billing_customers(id),
 ADD CONSTRAINT fk_bc_price_band FOREIGN KEY(price_band_version_id) REFERENCES billing_price_band_versions(id);

ALTER TABLE stripe_webhook_events ADD COLUMN billing_contract_id BINARY(16) NULL, ADD COLUMN billing_customer_id BINARY(16) NULL,
 ADD COLUMN stripe_object_ref VARCHAR(255) NULL, ADD COLUMN payload_sha256 CHAR(64) NULL,
 ADD COLUMN failed_at DATETIME(6) NULL, ADD COLUMN attempt_count INT NOT NULL DEFAULT 0,
 ADD KEY idx_swe_billing_contract(billing_contract_id), ADD KEY idx_swe_billing_customer(billing_customer_id), ADD KEY idx_swe_retry(failed_at,attempt_count),
 ADD CONSTRAINT fk_swe_billing_contract FOREIGN KEY(billing_contract_id) REFERENCES billing_contracts(id),
 ADD CONSTRAINT fk_swe_billing_customer FOREIGN KEY(billing_customer_id) REFERENCES billing_customers(id);

-- permission catalog は実表 permissions(name, display_name, scope) を用いる。
INSERT IGNORE INTO permissions (name, display_name, scope, created_at, updated_at) VALUES
 ('MANAGE_TEAM_BILLING', 'チームの料金・契約を管理', 'TEAM', NOW(), NOW()),
 ('MANAGE_ORGANIZATION_BILLING', '組織の料金・契約を管理', 'ORGANIZATION', NOW(), NOW());

-- ADMIN は既定付与する。DEPUTY_ADMIN の role_permissions 行は作らず、
-- BillingAccessGuard 専用の permission_groups 経路だけで当該scopeの明示付与を判定する。
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW() FROM roles r CROSS JOIN permissions p
WHERE r.name='ADMIN' AND p.name IN ('MANAGE_TEAM_BILLING','MANAGE_ORGANIZATION_BILLING')
  AND NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);

CREATE TABLE billing_api_idempotencies (
 id BINARY(16) NOT NULL COMMENT 'UUIDv7', actor_id BIGINT UNSIGNED NOT NULL, http_method VARCHAR(8) NOT NULL, request_path VARCHAR(255) NOT NULL,
 idempotency_key CHAR(36) NOT NULL, request_hash CHAR(64) NOT NULL, status VARCHAR(16) NOT NULL, response_status SMALLINT NULL, response_json JSON NULL,
 lease_owner VARCHAR(64) NULL, lease_expires_at DATETIME(6) NULL, started_at DATETIME(6) NOT NULL, completed_at DATETIME(6) NULL, expires_at DATETIME(6) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id), UNIQUE KEY uk_bai_actor_request(actor_id,http_method,request_path,idempotency_key), KEY idx_bai_expiry(expires_at), KEY idx_bai_lease(status,lease_expires_at),
 CONSTRAINT chk_bai_status CHECK(status IN ('PROCESSING','SUCCEEDED','FAILED')), CONSTRAINT chk_bai_response CHECK(response_status IS NULL OR response_status BETWEEN 200 AND 599)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消費者変更APIの冪等応答';

CREATE TABLE billing_return_state_nonces (
 id BINARY(16) NOT NULL COMMENT 'UUIDv7', nonce_hash CHAR(64) NOT NULL, purpose VARCHAR(24) NOT NULL, actor_id BIGINT UNSIGNED NOT NULL,
 scope_kind VARCHAR(8) NOT NULL, scope_id BIGINT UNSIGNED NOT NULL, organization_id BIGINT UNSIGNED NULL, stripe_session_ref VARCHAR(255) NULL, billing_customer_id BINARY(16) NULL,
 expires_at DATETIME(6) NOT NULL, consumed_at DATETIME(6) NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id), UNIQUE KEY uk_brsn_nonce(nonce_hash), KEY idx_brsn_expiry(expires_at,consumed_at), KEY idx_brsn_actor_scope(actor_id,scope_kind,scope_id), KEY idx_brsn_org(organization_id),
 CONSTRAINT fk_brsn_customer FOREIGN KEY(billing_customer_id) REFERENCES billing_customers(id), CONSTRAINT chk_brsn_purpose CHECK(purpose IN ('CHECKOUT_SUCCESS','CHECKOUT_CANCEL','PORTAL_RETURN','PAYMENT_ACTION_RETURN')), CONSTRAINT chk_brsn_scope CHECK(scope_kind IN ('USER','TEAM','ORG'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='return state一回消費nonce。URL/メール等PIIは保存しない';

-- V198（PR4 Checkout の穴埋め）: 契約 -> Checkout Session 参照と照合キュー。
-- psp_subscription_ref は webhook の Subscription 逆引き専用（F08.9 会費との分離キー）ゆえ流用せず別列とする。
ALTER TABLE billing_contracts ADD COLUMN stripe_checkout_session_ref VARCHAR(255) NULL COMMENT 'Stripe Checkout Session ID（cs_xxx・二重Session防止の正本）' AFTER psp_subscription_ref,
 ADD UNIQUE KEY uk_bc_checkout_session(stripe_checkout_session_ref);

CREATE TABLE billing_checkout_reconciliations (
 id BINARY(16) NOT NULL COMMENT 'UUIDv7', stripe_session_ref VARCHAR(255) NOT NULL, stripe_customer_ref VARCHAR(255) NOT NULL, idempotency_id BINARY(16) NOT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'PENDING', attempt_count INT NOT NULL DEFAULT 0, last_error_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id), UNIQUE KEY uk_bcr_session(stripe_session_ref), KEY idx_bcr_pending(status,created_at), KEY idx_bcr_customer(stripe_customer_ref),
 CONSTRAINT chk_bcr_status CHECK(status IN ('PENDING','RESOLVED','FAILED')), CONSTRAINT chk_bcr_attempt CHECK(attempt_count>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Checkout照合キュー。Stripe不透明IDと退避識別子のみ保持しPII/tokenは持たない';
```

Checkout Session は `billing_contracts.stripe_checkout_session_ref` へ**条件付き UPDATE（CAS）**で紐付ける。条件は「対象契約が実在し、論理削除されておらず、`status=PENDING` であり、Session ref が未設定か同一 ref であること」であり、同一 ref の再送だけを冪等に通す。これにより**再送時に「この契約は既に Session を持つ」を DB だけで判定でき**、既に別 Session を持つ契約への上書き＝Stripe の二重 Session 作成をアプリ側で確実に弾ける。「別契約が同じ Session を握る」経路は `uk_bc_checkout_session` が DB 側で塞ぐ。更新行数が 1 でなければ握りつぶさず例外を投げ、照合キュー退避＋502 へ倒す。`psp_subscription_ref` は webhook の Subscription 逆引き専用（F08.9 会費との分離キー）であり流用しない。

「Stripe 側に Checkout Session が実在するのに DB 側が倒れた」事案は `billing_checkout_reconciliations` へ耐久化する。`uk_bcr_session` により同一 Session の再 enqueue は行を増やさず `attempt_count` を積み、`status` を `PENDING` へ戻す（新たな失敗観測なので再び回収対象に載せる）。未回収の回収対象は `SELECT COUNT(*) FROM billing_checkout_reconciliations WHERE status='PENDING'` で数える。保持するのは Stripe の不透明 ID（`cs_...` / `cus_...`）と退避識別子だけであり、Checkout URL・return state token・client secret・raw payload・PII は保存しない。クロスドメイン FK は張らず索引のみを持つ。DB への退避自体が落ちた場合に限り marker 付き ERROR ログ（`BILLING_CHECKOUT_RECONCILIATION_REQUIRED`）を最後の砦として残す。

価格は `plans.base_monthly_price_jpy` / inline `price_data` を販売正本にしない。親`billing_price_versions`は不変の`catalog_revision`/`revision_no`（金額もStripe Priceも持たない）、可変CAS専用の`lock_version`を持つcatalog revision、子`billing_price_band_versions`はflat priceを含む唯一のsellable正本（Money、税込属性、税内訳、Stripe Price）である。V196はPLAN/ADDONの既存有効設定からDRAFT親revisionとDRAFT bandだけをDBへExpandし、Flywayは外部Stripe APIを呼ばない。運営はDRAFT revision+bandsを作成後にProvisionを開始し、revision/bandをPROVISIONINGへCASする。各bandは入力`inputAmount`、`taxBehavior`、`taxCode`からTax master snapshotとJPY端数規則で`amount_excluding_tax`/`tax_amount`/`amount_including_tax`を導出し、Stripe Priceの`unit_amount`/`tax_behavior`/tax code と一致させる。jobはband単位でStripe Priceを作成又はmetadata照合し、成功bandをREADY、失敗bandと親をPROVISION_FAILED（error code/attempts保存）へ置く。retryは失敗bandだけをidempotentに再実行する。DRAFT/READYは現ACTIVE Aのopen-ended期間と、future Bの`effective_from`境界を共存できる。**全bandがREADYかつStripe ref/metadata一致した場合だけ**activate APIがlockVersion CASし、Bが未来ならB/全bandをSCHEDULED、同一transactionでAの`effective_until=B.effective_from`を設定する。Bの開始時刻にschedulerがA/全bandをRETIRED、B/全bandをACTIVEへ原子的に遷移する。即時Bはactivate transaction内で同じ遷移を行う。次のfuture CもBを現行として同じ境界で予約する。部分成功、同時activate、Stripe/DB失敗では販売停止のままである。quote/preview/change/contractは`price_band_version_id`をFK保存し、invoice lineのStripe Priceと税導出結果がそのband snapshotと一致し、bandがACTIVE又はAPPLIED、又は§266の保存済FK+Stripe Price+金額/税snapshot照合済みRETIRED履歴bandでなければWebhook投影をretryable失敗にする。既存契約は保存済bandを維持し、次周期の価格確定だけが時点有効な新ACTIVE revisionを選ぶ。`billing_contract_changes` は **PLAN契約だけ**の変更Sagaであり、Service がfrom/to bandの親 `product_kind=PLAN` を検証する。ADDON は各 `billing_contracts(contract_kind=ADDON,feature_key)` を独立にCheckoutし、期末取消は `cancel_at_period_end`/`valid_until=current_period_end`、`invoice.paid` と明細投影をPLANと同じband正本で処理する。Customer 作成は DB の `uk_bcu_scope` reservation（`PROVISIONING`）→Stripe Customer create（customerId由来Idempotency-Key）→ref保存+`ACTIVE` の Saga とする。Stripe失敗は `PROVISION_FAILED`（error code/attempt countを保存）として同一予約行だけを安全に再試行する。Stripe成功後のDB失敗は Customer metadata `orphaned=true` と照合キューへ送り、再実行時にmetadataで同じCustomerを回収して `ACTIVE` 化する。ACTIVE 以外のCustomerでCheckout/Portalを開始してはならない。V196 適用前に既存 `billing_contracts` のPSP参照長（各255以下）・非NULL subscription ref一意性・status/履歴件数を番人クエリで検証し、不適合ならALTERを開始しない。V151既存行を用いる migration IT で参照値、status、履歴件数が不変であることを観測する。

販売用 **now selector**（公開pricing/quoteが共用）は、まず対象product/scopeのdue SCHEDULED B（`effective_from <= now < effective_until（NULL=∞）`）と直前ACTIVE Aの全bandを`SELECT ... FOR UPDATE`でlockする。Bがあれば同一transactionでA/全bandをRETIRED、B/全bandをACTIVEへ**冪等lazy-promote**し、commit後にselectorを再読込する。既にBがACTIVE又は別要求がpromotion済みなら状態を再書込せず再読込だけを行う。この後だけ`effective_from <= now < effective_until（NULL=∞）`かつstatus=ACTIVEのbandを一意に選ぶ。したがって00:00のschedulerが遅延しても最初の公開pricing又はquoteが時刻基準でBを選び、Aへ戻らない。scheduler/reconcileも同一lock/状態遷移を使い、遅延を検知した場合に収束させる。前月末23:55 JSTの**次期 selector**は`targetPeriodStart`（翌月1日00:00 JST）に同じ有効区間を満たすstatus=SCHEDULED又はACTIVEのbandを一意に選び、member count snapshotと共に予約する。contract/invoiceのWebhook照合は現行selectorを再実行せず、保存済`price_band_version_id` + Stripe Price ref + 金額/税snapshotが一致するならRETIRED履歴bandも正当として受理する。これは販売可否のACTIVE/APPLIED判定をcontract/invoice照合へ流用しない明示的な例外である。したがってA invoiceがB開始後に先着/遅着してもAの保存FKで一回だけ投影される。

TEAM/ORGの契約後人数変更は即時課金・即時権利変更をしない。各JST月の価格確定時刻は**前月末23:55:00 JST**であり、その時点の有効メンバー数を`member_count_snapshot`へ固定する。全 mutation の唯一の耐久leaseは `active_billing_contract_operation_pointers(contract_id PK)` である。`PLAN_CHANGE`、`MEMBER_REPRICE`、cancel/resume/migration/refund のいずれも、contract行を`SELECT ... FOR UPDATE`してversion CASし、operation INSERT とこのpointer reservationを同一transactionでcommitしてからだけStripeを呼ぶ。`billing_contract_changes.operation_id`は当該`PLAN_CHANGE` operationを一意に参照する従属レコードであり、旧change専用pointerはV196で廃止する。同一contract/翌月periodの`billing_membership_price_adjustments`は`contract_id,period_start` UNIQUEで一つだけとし、次期 selectorから一意bandを選択する。MEMBER_REPRICEはSchedule作成成功後も、webhookでAPPLIED/FAILED/CANCELLEDになるまで同じleaseを保持する。後発cancelはそのScheduleをcancel/releaseしてからCANCEL operationへ置換、migrationはadjustmentをCANCELLED/deferにしてからMIGRATION operationへ置換する。downgrade PLAN_CHANGEと同じ翌月phaseなら既存PLAN_CHANGE operationに人数bandを合成し、別Scheduleを作らない。Stripe metadataには`adjustmentId`、contractId、scope/customerを必須に置き、invoice/Subscription webhookはadjustmentId→operation→contractで所有確認する。増減・複数membership event・月末同時eventは同じ未確定operationをversion CASで上書きし、確定時刻後は翌々月候補へ送る。Schedule作成失敗は旧band/旧権利を維持して`FAILED`→reconcile/retry、作成済Schedule更新失敗又は順不同eventはmetadataと最新Stripe objectで再照合して`RECONCILIATION_REQUIRED`へ補償する。翌月`invoice.paid`のStripe Priceが予約bandと一致して初めてcontract bandと翌月権利を原子的に切替える。SYSTEM起因の月次job/webhookは`actor_kind=SYSTEM,created_by=NULL`で監査する。USER/flat bandも同じ月次ジョブを通るがmember_countは1固定である。

PLAN change は`PLAN_CHANGE` operationを先に予約し、そのoperationを参照する`billing_contract_changes`を同一DB transactionで作る。commit後にだけ Stripe を `Idempotency-Key=billing-operation-{operationId}` で呼ぶ。Stripe失敗はoperation/changeをFAILEDへCASし唯一のoperation pointerをDELETE、Stripe成功後のDB失敗はobject metadataのoperationId/changeIdで再照合して補償する。`APPLIED`/`FAILED`/`CANCELLED`だけがterminalであり同一transactionでpointerをDELETEする。`RECONCILIATION_REQUIRED`はterminalではないquarantineで、pointerを保持して全mutationを409にし、reconcile workerがStripe/metadata/DBを再照合してAPPLIED/FAILED/CANCELLEDへ確定した場合だけpointerを解放する。したがって同一contractの並行change/cancel/migration/member repriceは常に一件だけ進行する。

quote/change requestはclientの`priceVersionId`又は`priceBandVersionId`を受けずproductKind/productKeyだけを受ける。serverはtransaction内でmember countを確定し、公開pricingと共用の§266 now selector（due SCHEDULEDのrow lock/lazy-promote→再読込を先行）から当該scope/productで時点有効なACTIVE bandを**一意に**選び、そのbandをquoteへ焼き付ける。20/21、50/51、同時人数変動はいずれもこの選択を再実行し、結果が変わればQUOTE_STALEにする。operator登録は同一product/scopeの有効区間・人数範囲の重複を`SELECT ... FOR UPDATE`で検出して拒否し、ACTIVE化は対象時点に一件だけとする。

`billing_quotes` と `billing_change_previews` は、actor/scope/customer/product/priceVersion/memberCount/tax/amount/period/prorationDate/request hash/expiry/version を不変snapshotとして保存する。previewはopaque tokenを使わずUUID `previewId` 参照方式に統一し、actor/scope/contract/versionを照合して `UPDATE ... SET consumed_at=now,version=version+1 WHERE id=? AND actor_id=? AND consumed_at IS NULL AND expires_at>now AND version=?` のCASで一回だけ消費する。quoteも同じCAS方式である。Checkout/change は再計算値とこのsnapshotが完全一致した場合だけ作成できる。価格、人数、税、契約version、期間のいずれかが異なれば新規quote/previewを要求し、旧値での確定はしない。

有償upgradeでSCAが必要なら `REQUIRES_ACTION` とし、`POST .../changes` は `changeId`、`status='REQUIRES_ACTION'`、`effectiveAt`だけを返す。clientSecretの発行は `GET .../payment-action` だけに一本化する。GET時にサーバーはpurpose=`PAYMENT_ACTION_RETURN`のreturn stateをHttpOnly/Secure/SameSite=Lax cookieへ保存し、fresh `paymentAction:{type:'CONFIRM_PAYMENT',clientSecret,expiresAt}`を本人へ返す。`expiresAt`は`min(pending_update.expires_at, now+15分)`であり、PaymentIntentのexpiryを表さない。`clientSecret` はURL、return state、DB、browser storage、ログ、監査に保存しない。FE は既存 Stripe.js `PaymentElement`/`confirmPayment` を再利用し、固定clean return URL `/billing/payment-action/return` だけを`return_url`へ渡す。CSP の `js.stripe.com` / Stripe frame 許可を既存決済CSPへ明示追加する。confirm後はsummaryをpollし、再ログイン/別端末では `GET .../summary` とpending update eventでresumeする。`invoice.payment_action_required`、`customer.subscription.pending_update_applied`、`...expired` は同一changeを照合し、paidまで新権利を発行しない。

`GET /me/billing/summary` は`PendingChange={id,contractId,status:'PENDING_PAYMENT'|'REQUIRES_ACTION'|'CREATING_SCHEDULE'|'SCHEDULED',paymentActionRequired:boolean,effectiveAt}`を返す。`GET /me/billing/contracts/{id}/payment-action` はBillingAccessGuard後にStripe最新Invoice/PaymentIntentを取得し、`requires_action`かつそのactor/scope/contractのchangeだけへfresh clientSecretを再発行する。同時にサーバーは`Set-Cookie: billing_return_state=...; HttpOnly; Secure; SameSite=Lax; Path=/billing/payment-action/return`を発行し、cookie内stateのpurposeは`PAYMENT_ACTION_RETURN`、有効期限は`min(pending_update.expires_at, now+15分)`とする。confirmPaymentの`return_url`は固定 `/billing/payment-action/return` で、redirect先のBackend/Nitro callback自身がcookieをserver-sideでconsumeし、HMAC→purpose/expiry→actor/Guard→nonce CAS後にclean 303 `/billing?...`へ遷移する。Stripe/issuer起点top-level GETのOrigin/Refererは要求しない。bodyを送るconsume APIは設けない。他状態は409でsecret/cookieを返さない。`invoice.payment_failed`はPaymentIntentが`requires_action`又はpending_updateが存続中ならREQUIRES_ACTIONを単調維持し、definitive decline/void/pending_update_expiredだけFAILEDにする。

cancel/resume/downgrade-to-cancelは `billing_contract_operations` とactive pointerを同一transactionでCAS予約→commit→Stripe呼出→CAS結果反映する。期末ちょうどはStripeの最新subscription period endとDBのeffectiveAtを再読して片方だけを適用する。Schedule cancel後のsubscription cancelが失敗したら、Scheduleを再作成できる場合だけ補償し、できなければ `RECONCILIATION_REQUIRED` と運営incidentにする。UIはoperation中のボタンを無効化し、失敗は明示エラー後にsummaryをrefetchする。

価格backfillは **Expand→Provision→Contract** の三段階である。Flywayは外部Stripe APIを呼ばず、金額を持たないDRAFT catalog revisionと、Stripe refなしのDRAFT bandを作るだけにする。冪等service jobは**bandごと**にStripe Priceを作成/metadata照合してREADYまで昇格するだけであり、SCHEDULED/ACTIVEへの遷移は全band READYを確認したactivate APIだけが行う。既存subscription itemのPriceはreconcileして一致しない契約を販売/変更停止にする。placeholder Priceは作らない。

Checkout、SetupIntent、off-session subscription updateは専用 `payment_method_configuration` を参照し、card/Linkだけを有効にする。payment method typeを個別APIで直指定しない。同configurationが利用不能なStripe APIではcard/Linkへ同値fallbackし、既存保存PMが別typeの場合のSetupIntent/Checkout/更新失敗をE2Eする。

## 6. legacy Customer 移行 Saga

TEAM/ORG の operator-owned subscription は Customer を付替えず、通常 Checkout `create_prorations` も使わない。

1. `billing_contract_operations(kind=MIGRATION)` と既存`active_billing_contract_operation_pointers`を同一transactionで予約してから`billing_customer_migrations=CREATED`を作り、新 scope Customer を確保する。cancel/resume/DOWNGRADE_TO_CANCELとmigrationは同じcontractで相互排他であり、先行pointerがある操作は409、両方向同時要求も一方だけが予約成功する。migration terminal時は同一transactionでoperation pointerを削除し、失敗補償中は保持する。
2. `POST /migrations/{id}/setup-intent` は新scope Customerに束縛したSetupIntentをserver作成し、アプリ独自TTL **15分**の`setup_intent_expires_at`と`stripe_setup_intent_ref`をCAS保存して`SETUP_INTENT_CREATED`へ進め、clientSecretだけを返す。これはSI物理失効ではなく再発行の運用期限であり、Stripeの汎用expiryを契約しない。completion/期限workerはmigration行を`SELECT ... FOR UPDATE`して直列化する。completionはclient supplied refを受けず、**先に**DB refでStripeからSetupIntentをretrieveしcustomer、metadataのmigrationId/contractId/scope/customerId、payment methodを厳密照合する。retrieve結果が`succeeded`なら15分後の到着も受理してPM refを保存し`PAYMENT_METHOD_COLLECTED`へ進める。期限超過かつ未完了ならCASでcancel→再retrieveし、succeeded競合なら成功を優先、未完了だけDB refを失効化して`CREATED`へ戻し新SIを再発行可能にする。成功時はPMをCustomerとSchedule `default_settings.default_payment_method`へ設定する。通常のCustomer Portalは支払方法変更だけに使い、migration収集には使わない。旧個人 Customer のカード/請求先は読まない。
3. 成功後、`start_date=current_period_end`、`end_behavior=release`、phase `start/end_date`、price/quantity、`proration_behavior=none`、default PMを明示した **Stripe Subscription Schedule** を作る。Schedule metadata と `phases[0].metadata` の双方にmigrationId/contractId/scopeKind/scopeId/billingCustomerIdを必須で置く。開始前の invoice はゼロ、`SCHEDULE_CREATED`。
4. schedule 作成成功を永続化した後だけ旧 subscription を `cancel_at_period_end=true` にして `OLD_CANCEL_SCHEDULED`。
5. 移行中にlegacy Customerのinvoiceを受理できるのは `contract_id` と `legacy_psp_subscription_ref` がmigration行に**完全一致**し、かつstatusが `OLD_CANCEL_SCHEDULED` までの場合だけである。legacy Customer全体、他subscription、切替後 `COMPLETED` のinvoiceはbilling所有にしない。
6. schedule start と新 invoice.paid を確認して `COMPLETED`。新 scope Customer の contract を正規所有者として切替える。旧subscriptionが期末で終了した後に新支払が失敗した場合は旧cancelを解除せず `NEW_PAYMENT_PAST_DUE` として旧権利を復活させない。新支払の再試行と運営incidentだけを許可する。
7. legacy契約でも移行/新カードなしにexact旧subscriptionを一確認で期末cancelできる。cancel後はmigration開始不可、進行中migrationとはoperation pointerで競合409とし、cancel失敗の補償はschedule cancel/release→旧subscription状態再読→必要時RECONCILIATION_REQUIREDである。
8. step 3〜5 のうち旧subscription終了前の失敗は `COMPENSATING` とし、新 schedule を cancel/release、旧 cancel を `false` へ戻す。両方成功で `COMPENSATED`、戻せない場合だけ `FAILED`＋運営照合。旧 Customer/delete/detach はしない。

## 7. API 契約

公開価格は `GET /api/v1/public/billing/plans?scopeKind={USER|TEAM|ORG}`（認証不要、`scopeKind`必須、200）で返す。Controller に `@IntentionallyPublic("/api/v1/public/billing/plans")` を付け、SecurityConfig はこの **GETだけ**を exact `permitAll`、`PublicApiRateLimitFilter` はIPごと60回/分を適用する。応答は `data:{scopeKind,plans:PublicPlan[],addons:PublicAddon[]}`、`PublicPlan={planKey:string,displayNameKey:string,descriptionKey:string,startingMonthlyTotal:Money?,priceBands:PublicPriceBand[],quoteRequired:boolean,available:boolean,featureKeys:string[]}`、`PublicAddon={featureKey:string,displayNameKey:string,descriptionKey:string,startingMonthlyTotal:Money?,priceBands:PublicPriceBand[],quoteRequired:boolean,available:boolean}` とする。`PublicPriceBand={minMembers:int32,maxMembers:int32?,startingMonthlyTotal:Money?}`。USER は `startingMonthlyTotal` を表示できるが、人数が未確定のTEAM/ORG は確定額を表示せず、`quoteRequired=true` と「ログイン後に対象チーム/組織で確定見積り」を表示する。`available=false` のとき全価格はnull、Checkout/Stripe ref/個別 scope情報は返さない。全認証後 API は `BillingAccessGuard` を通す。`/pricing` はlanding layoutのSSR `useAsyncData`でこのDTOを取得し、guest/authのリダイレクトを置かない。canonical `/pricing`、SEO title/description、loading/error skeleton、hydration一致を必須とする。

以下の表は特記なき限りすべて `/api/v1` prefix を省略している（例: `GET /me/billing/scopes` は `GET /api/v1/me/billing/scopes`）。

| API | request（型/必須） | response `data` | status |
|---|---|---|---|
| `GET /me/billing/scopes` | なし | `items: Scope[]{kind enum,id int64,name string,manage boolean}` | 200 |
| `GET /me/billing/summary`（PR9で実装） | `scopeKind enum`,`scopeId int64` | `scope`,`plan: Contract?`,`addons: Contract[]`,`pendingChanges:PendingChange[]`,`nextInvoice: Money?`,`quoteWindow:{available,availableAt?}` | 200/403 |
| `GET /me/billing/invoices` | scope + `cursor:string?` + `size:int[1,100]=20` | `data: InvoiceSummary[]`, `meta:{nextCursor:string?,hasNext:boolean}` | 200/403 |
| `GET /me/billing/invoices/{id}` | UUIDv7 | `InvoiceDetail{lines:InvoiceLine[],adjustments:InvoiceAdjustment[],issuer,billingAddress?,subtotal,discount,total}` | 200/403/404 |
| `POST /me/billing/quotes` | header key; `{scopeKind,scopeId,productKind:'PLAN'|'ADDON',productKey:string}` | `{quoteId:UUID,productKind,productKey,initialTotal:Money,nextMonthlyTotal:Money,expiresAt:datetime(10分),periodStart,periodEnd}` | 201/403/409 |
| `POST /me/billing/checkout-sessions` | header key; `{quoteId:UUID}` | `{checkoutUrl:https URL,expiresAt:datetime}` | 201/403/409/502 |
| `POST /me/billing/contracts/{id}/change-previews` | header key; `{toProductKind:'PLAN',toProductKey:string,version:int64}` | `{previewId:UUID,kind,amountDueNow:Money,effectiveAt,expiresAt}` | 201/403/404/409 |
| `POST /me/billing/contracts/{id}/changes` | header key; `{previewId:UUID,version:int64}` | `{changeId:UUID,status enum,effectiveAt}`（SCA時もsecretなし） | 202/403/404/409/502 |
| `GET /me/billing/contracts/{id}/payment-action` | contract UUIDv7 | `{changeId:UUID,status:'REQUIRES_ACTION',paymentAction:{type:'CONFIRM_PAYMENT',clientSecret:string,expiresAt:datetime}}` + HttpOnly `PAYMENT_ACTION_RETURN` cookie | 200/403/404/409/502 |
| `POST /me/billing/contracts/{id}/cancel` | header key; `{version:int64}` | `{scheduledAt,endAt,status:'SCHEDULED'}` | 200/403/404/409/502 |
| `DELETE /me/billing/contracts/{id}/cancel` | header key + `version` query int64 | `{endAt,status:'ACTIVE'}` | 200/403/404/409/502 |
| `POST /me/billing/portal-sessions` | header key; `{scopeKind,scopeId}` | `{url:https URL,issuedAt:datetime}` | 201/403/409/502 |
| `POST /me/billing/migrations` | header key; `{contractId:UUID,version:int64}` | `{migrationId:UUID,status}` | 202/403/404/409/502 |
| `GET /me/billing/migrations/{id}` | UUIDv7 | `{id,status,effectiveAt,paymentMethodRequired,canRetry,version}` | 200/403/404 |
| `POST /me/billing/migrations/{id}/setup-intent` | header key; `{version:int64}` | `{setupIntentId:string,clientSecret:string,expiresAt:datetime,status:'SETUP_INTENT_CREATED'}` | 201/403/404/409/502 |
| `POST /me/billing/migrations/{id}/payment-method-completions` | header key; `{version:int64}` | `{status}` | 202/403/404/409/502 |
| `POST /me/billing/migrations/{id}/retry` | header key; `{version:int64}` | `{status}` | 202/403/404/409/502 |
| `POST /webhooks/stripe` | Stripe raw body/signature | empty | 200/400/500 |

`Money={currency:'JPY',amountIncludingTax:int64,amountExcludingTax:int64,taxAmount:int64,taxName:string?,taxRateBasisPoints:int?}`。`TaxBreakdown={taxName:string?,taxRateBasisPoints:int?,taxAmount:int64}`。`Scope={kind:'USER'|'TEAM'|'ORG',id:int64,name:string,manage:boolean}`。`ContractBase={id:UUIDv7,status:'PENDING'|'ACTIVE'|'PAST_DUE'|'CANCELLED'|'EXPIRED',priceBandVersionId:UUIDv7?,currentPeriodEnd:datetime?,cancel:{scheduledAt:datetime,endAt:datetime}|null,canCancel:boolean,canResume:boolean,version:int64}`、`Contract=ContractBase & ({contractKind:'PLAN',planKey:string,featureKey:null}|{contractKind:'ADDON',planKey:null,featureKey:string})` とする。したがって `plan` は `contractKind='PLAN'` の `Contract` 又はnull、`addons` の各要素は `contractKind='ADDON'` の `Contract` のみであり、`planKey` と `featureKey` は必ず一方だけが非null（両null・両non-null・不一致 kind はfail-closed）である。各 Contract の `id` と `version` は cancel/resume request にそのまま渡し、`cancel`/`canCancel`/`canResume` で個別の期末取消・撤回可否と終了日を描画する。`InvoiceAdjustment={id:UUIDv7,kind:'REFUND'|'CREDIT_NOTE'|'DISPUTE',amount:int64,currency:'JPY',status:'PENDING'|'SUCCEEDED'|'FAILED'|'OPEN'|'WON'|'LOST'|'CLOSED',reason:string?,effectiveAt:datetime}`。`InvoiceSummary={id:UUIDv7,status:'DRAFT'|'OPEN'|'PAID'|'UNCOLLECTIBLE'|'VOID',periodStart:datetime?,periodEnd:datetime?,total:Money,paidAt:datetime?}`。`InvoiceLine={description:string,quantity:decimal,amountExcludingTax:int64,discount:int64,taxes:TaxBreakdown[],amountIncludingTax:int64,periodStart:datetime?,periodEnd:datetime?}`。`Address={country:string,line1:string,city:string?,postalCode:string?}`。`InvoiceDetail=InvoiceSummary & {lines:InvoiceLine[],adjustments:InvoiceAdjustment[],issuer:{name:string},billingAddress:Address?,subtotal:Money,discount:Money}`。返金・credit note・dispute は複数の `adjustments[]` であり、invoice lifecycle の `status` を上書きしない。すべての日時は ISO-8601 offset 付き、nullable は `?` のみ、ただし上記 XOR のキーは明示 `null` を返す。未知 enum は fail-closed とする。Portal URL は JSON の短命 URL に統一し 302 を使わない。サブスクの証憑（PDF・原本保存・インボイス登録番号）は Mannschaft 自前の invoice 投影ではなく F08.12 第2段が担い、本APIは document-url を提供しない。change previewはUUID `previewId`をactor/scope/contract/version/request hashに束縛し、最大10分で失効、CASで一回だけ消費する。idempotency は `billing_api_idempotencies` の `actor_id,method,path,key` UNIQUE、同 key の hash 相違は409、同一は保存済 response を返す。

OpenAPI は `BillingPlanContractResponse` と `BillingAddonContractResponse` を `@Schema(oneOf={...}, discriminatorProperty="contractKind")` で `Contract` に束ねる。生成TypeScriptのdiscriminated unionが `plan`/`addons` をnarrowできることをcompile ACにする。全ID指定APIは、存在するが他scopeのobjectを404、管理scopeだが操作不可を403とし、共通 `ErrorResponse` のdetails mapを拡張しない。409の月末/quote/preview衝突だけは専用 `BillingConflictDetails{reason:'MONTH_BOUNDARY'|'QUOTE_STALE'|'QUOTE_EXPIRED'|'PREVIEW_EXPIRED'|'CHANGE_CONFLICT',availableAt?:datetime,quoteId?:UUID}` をBusinessException/handler/OpenAPIで返す。

return state はサーバーだけが作る `opaque HMAC` tokenであり、payload は `{purpose:'CHECKOUT_SUCCESS'|'CHECKOUT_CANCEL'|'PORTAL_RETURN'|'PAYMENT_ACTION_RETURN',scopeKind,scopeId,tab,quoteId?,sessionId?,billingCustomerId?,iat,exp,nonce}`。HMAC secretは環境変数でkey-id付きrotationを行う。Checkout success/cancel、Portal return、3DS returnはそれぞれBackend/Nitro所有の固定同一origin route（`/billing/checkout/success`、`/billing/checkout/cancel`、`/billing/portal/return`、`/billing/payment-action/return`）でHMAC/期限/purposeを検証する。Stripe/issuer起点のtop-level GETでOrigin/Referer同一originを要求しない。未認証ならnonceを消費せずsigned stateをHttpOnly/Secure/SameSite=Lax短命`billing_return_state` cookieへ保存しloginへ303する。このときloginの`next`は**その callback 自身のパス**（`/billing/checkout/success` 等）とし、再認証後にcallbackが必ずもう一度呼ばれるようにする（`next=/billing`ではnonceが消費されず復帰導線が失われる）。4入口すべてがcookieからstateを受け取れる。query paramとcookieが両方来た場合は**query paramを優先**し（Stripe/issuerからの直接復帰が正、cookieは退避経路）、`PAYMENT_ACTION_RETURN`だけはquery paramを一切読まない。cookieが存在した要求では消費成否に関わらずcookieを失効させる。再認証後は同じBackend/Nitro callbackが**bodyを読まず**cookieをserver-sideで復号・削除し、HMAC→purpose/expiry→actor一致→BillingAccessGuard→nonce CASの順で消費してclean 303 `Location: /billing?...`を返す。別のconsume APIは設けない。3DS後loginでも`PAYMENT_ACTION_RETURN`が同じ手順で一回だけ消費される。Stripe URL作成成功後のDB更新失敗はmetadataのsession/customer refで照合キューが回収する。不正/期限切れ/nonce再利用はUSER hubのgeneric errorへ安全遷移する。token `exp` はCheckoutではSession expiry+15分（発行から最大24時間）、Portalでは`issuedAt+30分`である。**Checkout/Portal stateだけ**をサーバーがStripe URLへ埋め込み、PAYMENT_ACTION_RETURNは`GET /payment-action`の`Set-Cookie`（HttpOnly）だけで発行する。後者のtokenはJSON body・URL・JavaScriptへ公開しない。期限又は消費済み行は7日後cleanupとする。

Portalは専用Portal configuration IDを環境変数 `STRIPE_BILLING_PORTAL_CONFIGURATION_ID` で固定する。`subscription_update`、`subscription_cancel`、`subscription_pause` は無効、payment method、billing information、invoice historyだけを有効にする。起動時health checkでconfiguration取得値を照合し、不一致/取得不能はPortal開始をfail-closedにする。PLAN/ADDONの変更・解約をPortal経由で行えないことをACにする。

`billing_api_idempotencies` はcheckout/cancel/resume/change/migrationで、同一transactionのDB reservation（`PROCESSING`, lease owner/expiry）→commit→外部Stripe→`SUCCEEDED`又は`FAILED`へCAS確定の順に使う。同一keyでrequest hashが違えば409、処理中かつlease有効なら202又は409に `Retry-After` を付け、期限切れleaseは、観測したlease owner/expiryを条件に含めたCASで**次に同じキーで到達した要求自身**が所有権を回収し、勝った側だけが再実行する（負けた側は横取りせずPROCESSINGを返す）。これが無いと予約直後にworkerが落ちたキーがRECORD_TTL（24時間）詰まる。同一keyの同時到達でUNIQUE `uk_bai_actor_request` が競合した場合は、既存行を読み直して同じPROCESSING/REPLAY/409判定へ写し、5xxにしない。responseは成功/失敗確定までNULLを許容し、外部呼出し前に重複実行しない。

既存 `ENTITLEMENT_005`（scope forbidden/403）、`ENTITLEMENT_007`（contract not found/404）、`ENTITLEMENT_015`（Checkout失敗/502）、`ENTITLEMENT_016`（PENDING競合/409）、`ENTITLEMENT_017`（旧PUT互換409）を再利用する。新エラーは既存017の後に連番で予約し、`ENTITLEMENT_018`=invoice not found(404)、019=price not sellable(409)、020=preview expired(409)、021=change conflict(409)、022=month boundary(409)、023=quote expired(409)、024=migration required(409)、025=Stripe unavailable(502)、026=`BILLING_FLOW_REQUIRED`(409、旧有償POST専用)とする。実装マージ時はエラーregistry全体の最大値と重複しないことを再検証して `GlobalExceptionHandler` へ明示登録する。

### 7.1 互換URL、sidebar、アクセシビリティ

旧 `/teams/{slug}/settings/billing` と `/organizations/{slug}/settings/billing` は、認証後にcanonical slugをdetail lookupしてnumeric scopeIdへ解決し、`tab`だけを whitelist（plan/invoices/payment/cancel）して `/billing` へreplace遷移する。旧slugはcanonical slugへ301相当のreplace、未存在は404、他scope又は権限喪失は403であり、USERへの誤fallbackをしない。`/billing/plans` も同じwhitelist queryだけを移す。sidebarは `GET /me/billing/scopes` 結果で表示を決め、403時は金融情報stateを破棄して再取得する。

課金UIは入力label、キーボードtab順、modalのfocus trap/restore/Escape、成功/失敗のaria-live、色以外のstatus表示、table header、44px target、mobile横scroll代替を必須とする。`/pricing` と `/billing` をaxe+keyboardでja/enおよび最長locale文言でE2Eし、価格表・解約confirm・3DS戻りのfocusを観測する。

旧URLの最終遷移は次で固定する。`/settings/billing`（認証済み）は`/billing?scopeKind=USER&tab=plan`へclient replace、未認証はlogin後同URLへ戻す。`/teams/{current|old-slug}/settings/billing` とorganization版はcanonical lookup成功かつ管理可なら`/billing?scopeKind={TEAM|ORG}&scopeId={id}&tab={whitelist}`へclient replace、missing=404、forbidden=403である。`/billing/plans?scopeKind/scopeId/tab`は認可済みscopeだけ同値を移し、それ以外はUSERへscopeを置換する。旧checkout成功queryは署名stateなしに成功扱いにせずgeneric errorへ遷移する。

Stripe Test ClockではCustomer作成時に`test_clock`を設定し、アプリはinjectable `AppClock` をJST暦月判定へ使う。権利・DB遷移はStripe event/object timestampを正準、表示用時刻だけAppClockを使う。isolated billing projectはserial run又はworker固有scope/run-id metadataで衝突を防ぐ。cleanupの唯一の正本は `stripe_webhook_events → billing_invoice_lines → billing_invoice_adjustments → active_billing_contract_operation_pointers/active_contract_pointers → billing_customer_migrations → billing_membership_price_adjustments → billing_contract_changes/billing_contract_operations/billing_change_previews/billing_quotes/billing_return_state_nonces/billing_api_idempotencies → billing_invoices → billing_contracts → billing_price_band_versions → billing_price_versions → billing_customers` の厳密な逆FK順である。migration実装前に`information_schema.KEY_COLUMN_USAGE`を読み、実DBの全billing FKとこのcleanup列をtopologicalに突合するテストを必須にする。

## 8. 税、保持、監査、非機能

Stripe invoice/line を正本として金額を取得し、`subtotal - discount + tax = total`、各 line の税込/税抜/端数合計が invoice と一致しないと投影を確定しない。JPY は小数なし、Stripe の line amount を再丸めしない。invoice/line は割引、税名、税率、`is_included_in_price`、発行者/請求先 snapshot を不変保存し、bearer URL snapshot 列は持たない。請求書投影は7年保持し、Webhook raw payloadは永続化しない。

`BILLING_CHECKOUT_CREATED`、`BILLING_CHANGE_*`、`BILLING_CANCEL_*`、`BILLING_PORTAL_OPENED`、`BILLING_INVOICE_VIEWED`、`BILLING_MIGRATION_*`、`BILLING_OPERATOR_VIEWED`、webhook成功/失敗を actor/scope/object ref/金額で監査する（カード番号、住所全文、URL、payloadは除外）。一覧 P95 は500ms、cursor既定20最大100、表示では Stripe 同期呼出しをせず投影を読む。rate limit は scope ごとに checkout/change/cancel/Portal 各10回/時、CSP/ログ/例外はPIIを出さない。

## 9. テストと受入条件

| AC | ケース（種別/観測点） |
|---|---|
| BC-01 | 正常/外部失敗・結合: USER/TEAM/ORG 各 scope は`PROVISIONING`予約を一意に確保し、Stripe成功後だけref+ACTIVE。失敗はPROVISION_FAILED、orphan回収/再試行後に一意ACTIVE、非ACTIVEではCheckout/Portal不可 |
| BC-02 | 認可・結合: V196でTEAM ADMINには`MANAGE_TEAM_BILLING`、ORG ADMINには`MANAGE_ORGANIZATION_BILLING`だけを既定付与。DEPUTY/MEMBERには`role_permissions`を付与せず、同一scope permission groupの対応キーを明示付与されたDEPUTYだけを許可し取消後は即403。SYSTEM_ADMIN は消費者 invoice/Portal 403、他scope id は404 |
| BC-03 | 境界・単体: month-endのSession作成境界はBC-13を正本とし、旧`-30分-1秒`/quote-session同一期限仕様は使用しない。月初直後、うるう年、年跨ぎも観測 |
| BC-04 | 正常/外部失敗・Stripe fixture: 新規/upgrade は `invoice.paid` 前にACTIVE/権利なし、payment_failedで旧プラン維持 |
| BC-05 | 正常・Stripe test clock: downgrade schedule が翌月1日まで権利/金額を変えず、target Price/schedule phase/effectiveAt一致の`customer.subscription.updated`で一回だけ切替。ADDON新規/期末取消も同じinvoice/valid_until正本 |
| BC-06 | 境界・結合: 解約の前/ちょうど/後、撤回可否、`deleted`後EXPIRED/pointer削除/権利失効 |
| BC-07 | 並行・結合: 同一/異なる idempotency key、version競合、重複/順不同 webhook で二重契約・監査・請求なし。`invoice.paid` が `checkout.session.completed` より先でも subscription metadata から同一contractを解決し一回だけACTIVE化 |
| BC-07b | 並行/補償・結合: `active_billing_contract_operation_pointers` のcontract PKだけで同時change/cancel/migration/member repriceは1件だけ。APPLIED/FAILED/CANCELLEDだけでpointer削除し、RECONCILIATION_REQUIREDはquarantineとして保持・全mutation409、reconcile確定時だけ解放する。CAS競合とDB commit後Stripe失敗をmetadata再照合で補償 |
| BC-08 | legacy補償・E2E+fixture: app TTL 15分のSetupIntentをserver retrieveし、期限前/ちょうど/後、**14:59 succeeded→15:01 completion受理**、期限超過未完了だけSI cancel→再retrieve→CREATED→再発行、期限workerとcompletion同時のmigration row lock/cancel-vs-succeeded補償、customer/metadata/succeeded照合→default PM→metadata付future Schedule→旧cancelの順を観測する。schedule-startよりinvoice.paid先着、schedule失敗/旧cancel失敗でschedule取消・旧cancel撤回 |
| BC-09 | 税・結合: 必須inputAmount/taxBehavior/taxCodeからTax master snapshotとJPY端数で税込/税抜/税額を導出し、inclusive/exclusive両fixture、割引/税/請求先snapshotがStripe Price/invoiceと一致することを観測する。Tax未登録ではautomatic_taxなし |
| BC-10 | UI/E2E: `/pricing`、既存導線文脈、全scope切替、月別明細（Mannschaft自前画面表示、外部document URLへの遷移なし）、PLANと有償ADDONの個別表示、各ADDONの期末取消/撤回、解約一確認/撤回、6言語キー解決 |
| BC-11 | 公開・Security IT: 未認証 `GET /api/v1/public/billing/plans` は200、他methodは認証必須、`@IntentionallyPublic` とSecurityConfig exact GETが番人で一致、IP 61回目は429 |
| BC-12 | 認可・FE E2E: DEPUTY はscope一致permission groupだけで許可、`role_permissions`/cache経由は不許可。取消直後は403、金額state破棄→scopes再取得→USERへ。一般MEMBERのsummary/invoice/Portalは403、entitlements APIのみ既存挙動 |
| BC-13 | quote/session境界・結合: quoteは10分、Sessionは`min(now+23h59m,nextMonthStart-60秒)`、残り30分+60秒未満は022。Checkout前の価格/人数/税/period差異は未消費409 `QUOTE_STALE`、確定額はsnapshotと一致 |
| BC-14 | 3DS・Stripe fixture: `invoice.payment_action_required`でREQUIRES_ACTIONを返し、専用GETだけが短命clientSecretとHttpOnly PAYMENT_ACTION_RETURN `Set-Cookie`を本人へ返す。`payment_failed`がrequires_action/pending update存続なら単調維持、decline/void/expiredのみFAILED。confirmPayment後のpaidで一回だけAPPLIED、順不同fixture、URL/storage/log/監査にsecret/tokenなし |
| BC-15 | Webhook controller IT: 署名不正400、billing所有のretryable例外5xx、所有外はevent id未消費でF08.9 fallthrough/200。subscription ref missはmetadata厳密照合後にのみbind |
| BC-16 | return E2E: Checkout stateはSession expiry+15分（発行から最大24時間）、Portal stateはissuedAt+30分を別々に検証する。未認証callbackはnonce未消費でlogin、再認証後callback自身がHMAC/purpose/expiry→actor/Guard→CASの順で一回消費し303 clean reloadする。Stripe/issuer top-level GETのOrigin/Referer無しも正規許可し、不正は安全なUSER hubへ。Portal設定ID不一致はfail-closed、PortalでPLAN/ADDON変更/解約/停止不可 |
| BC-17 | legacy E2E: active migrationのexact contract+subscriptionだけlegacy invoiceを許容。旧sub終了後の新支払失敗はNEW_PAYMENT_PAST_DUE、旧cancel/権利を復元せずretry/incidentのみ。legacy exact旧subscriptionの一確認cancel、cancel後migration不可、進行migration競合/補償を観測 |
| BC-18 | price revision/Provision IT: SYSTEM_ADMINが不変catalogRevision/revisionNoとlockVersionを持つDRAFT revision+bandsを作成し、範囲/有効期間overlapを拒否する。Provisionの正常、Stripe失敗、部分成功、retry、同時activate CAS、全band READY後だけfuture BをSCHEDULED、開始時に現行AをRETIRED/BをACTIVE、次future Cも予約できることを観測する。公開pricing/quote共用now selectorがdue SCHEDULED BとAをrow lockして冪等lazy-promote→再読込し、同時二重promoteでも一回だけA→RETIRED/B→ACTIVEとなること、23:55→00:00とscheduler遅延、A invoiceがB開始後に先着する順不同でも保存FK/Stripe ref/snapshotでRETIRED Aを受理することを観測する。既存契約は次周期だけ新bandを選ぶ。FlywayがStripe APIを呼ばず、既存subscription itemのreconcile不一致は販売停止 |
| BC-19 | operation race・結合: cancel/resume/DOWNGRADE_TO_CANCELのpointer/CAS、期末ちょうど、schedule cancel後sub cancel失敗のRECONCILIATION_REQUIRED quarantine（pointer保持・全mutation409）とreconcileのAPPLIED/FAILED/CANCELLED確定/解放、UI pending/error/refetchを観測 |
| BC-20 | OpenAPI/UX E2E: Contract oneOf/discriminator生成型compile、IDOR 404/unauthorized scope403、専用ConflictDetails、canonical slug matrix、public pricing SSR/hydration/SEO、axe+keyboard ja/en/長文locale |
| BC-21 | 権限昇格負E2E: DEPUTYによるbilling permission group作成/更新/削除/自己割当/他scope recipientは403。ADMINだけが同scope DEPUTYへ割当でき、監査を残す |
| BC-22 | 人数band・Clock E2E: 20/21・50/51・同時人数変動でserverがnow selectorとtarget-period selectorを一意に選ぶ。23:55 snapshot→00:00 B、scheduler遅延中に公開pricing/quote同時要求がrow lock下で一回だけlazy promote→再読込すること、Schedule release後翌々月の更新、Test Clock Customer設定、AppClock/event timestamp正準、逆FK cleanupを観測 |
| BC-23 | idempotency/return E2E: checkout/cancel/change/migration同時要求のPROCESSING lease/Retry-After/stale recovery、success/cancel/Portal別nonce、303 clean reload、Stripe成功後DB失敗照合を観測 |
| BC-24 | 価格正本・人数E2E: 親catalog revisionにMoney/Stripe refが無くbandだけが持つDDL検査、flat/20-21/50-51のnow/target-period band選択、公開pricing/quoteのdue SCHEDULED row lock・同時二重lazy-promote・再読込、quote/preview/change/contract/invoice lineの保存FKとStripe Price/税snapshot一致、ACTIVE/APPLIED又は保存照合済RETIRED履歴band受理、前月末23:55→00:00・scheduler遅延・A invoice先着・増減/重複/月末同時/Stripe失敗補償、翌月invoice.paidでのみbandと権利が切替わることを観測 |
| BC-25 | renewal/refund/dispute E2E: renewal payment_failedはACTIVE→PAST_DUEかつ期間未延長、retry paidだけがACTIVE/次period延長、期末未払EXPIRED、change invoiceとの分岐、refund/credit note/disputeのinvoice投影・二重請求返金Saga・権利原則をfixture順不同で観測 |
| BC-26 | callback/migration/UI/cleanup E2E: 未認証callbackはnonceを消費せずHttpOnly signed-state→login、再認証後callback自身のHMAC/purpose/expiry→actor/Guard/CAS後だけ303、Origin/Referer無しStripe/issuer GETも許可、migration対cancel双方向同時409、全FKのinformation_schema照合と厳密逆順cleanupを観測 |
| BC-27 | legacy route E2E: 実在する旧POST/PUT/DELETE me/team/org contractsで無償ADDONのみ継続、旧有償POSTは409 `ENTITLEMENT_026` FLOW_REQUIRED、旧PUTは409 `ENTITLEMENT_017`、旧有償DELETEは新cancel Sagaへ同一lease/idempotencyで委譲、委譲不能410、旧/new同時・全scope/IDORを観測 |
| BC-28 | callback/3DS E2E: CHECKOUT_SUCCESS/CANCEL/PORTAL/PAYMENT_ACTION_RETURNの各purpose、3DS→login→GET payment-actionのHttpOnly `Set-Cookie`だけで発行されたcookieのserver-side consume→HMAC/purpose/expiry→actor/Guard→CAS一回消費→clean URL、Origin/Referer無しtop-level GET、body/URL/JS非公開・再利用/期限切れを観測 |
| BC-29 | contract mutex/refund E2E: MEMBER_REPRICE/change/cancel/migrationのrow lock/lease競合、downgrade人数band合成、cancel/migration defer、adjustmentId順不同補償、複数部分refund/credit note/dispute勝敗/失敗再送とinvoice aggregate分離を観測 |

CIはfake Stripe gateway+署名fixtureで全role/scope/PLAN/ADDON/legacy seedを実行する。FK cleanupの詳細順は05 §7.1（上記唯一の正本）を参照し、ここで別順を定義しない。実StripeはCIへ混ぜず、手動/stagingの隔離 `billing-stripe` projectだけでtest key、`stripe listen`、Test Clock、run-id metadata、advance→webhook drain→reconcile→Stripe cleanupをserial実行する。

## 10. ロールアウト/ロールバック

`read-only hub → price versions → scope Customer新規契約 → invoice投影 → Portal → cancel/resume → change → legacy migration` の順に feature flag で有効化する。各段で webhook遅延、Stripe/DB invoice差分、二重請求ゼロを監視する。異常時は新規作成 flag のみOFFにし、既存 Subscription/Webhook を止めない。作成済 Customer/invoice/audit を削除せず、legacy read path は全migration完了と照合後にだけ削除する。
