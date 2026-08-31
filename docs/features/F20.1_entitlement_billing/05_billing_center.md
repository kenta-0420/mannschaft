# F20.1 — 05 料金・契約センター／実決済運用

> **ステータス**: 🟡 精査中（2026-08-31）
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

消費者 API は **`BillingAccessGuard`** だけで認可する。SYSTEM_ADMIN の短絡許可はない。USER は本人、TEAM は `ADMIN` 又は `MANAGE_TEAM_BILLING` を明示付与された `DEPUTY_ADMIN`、ORG は `ADMIN` 又は `MANAGE_ORGANIZATION_BILLING` を明示付与された `DEPUTY_ADMIN` に限定する。一般 MEMBER は権利サマリだけを read でき、金額・請求先・invoice・Portal・変更は read できない。運営は別の `/api/v1/system-admin/billing/**` read-only API で、理由（1〜500文字）を必須にし `BILLING_OPERATOR_VIEWED` を監査する。運営 API は Portal、Checkout、変更、文書 URL を発行しない。

V196 Flyway は既存 Permission Catalog に大文字2キー **`MANAGE_TEAM_BILLING`（scope=TEAM）** と **`MANAGE_ORGANIZATION_BILLING`（scope=ORGANIZATION）** を登録し、それぞれ当該 scope の `ADMIN` にだけ既定付与する。`DEPUTY_ADMIN`、MEMBER、SUPPORTER への既定付与はしない。DEPUTY_ADMIN には既存ロール権限管理 UI/API で scope に対応するキーだけを明示付与/取消できる。`BillingAccessGuard` は scopeKind でキーを分岐して実効 permission を毎要求で検証する。ロール変更・付与/取消は `BILLING_MANAGE_GRANTED/REVOKED` を監査し、付与後/取消後のアクセスを即時に反映する。

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

通常 Checkout の quote/session は、Stripe 最短有効期限 30 分を前提に、`now <= nextMonthStart - 30分 - 1秒` のときだけ作成可能とする。これを過ぎた時点（`now >= nextMonthStart - 30分`）から月初までを拒否し、`ENTITLEMENT_022`(409) と `availableAt`（翌月1日00:00 JST）、`reason=MONTH_BOUNDARY` を返す。UI は「月初に最新金額を再見積りしてください」と表示する。作成した quote と session は同じ **`expiresAt=nextMonthStart-1秒`** を持つ。expired quote/session は確定 API で `ENTITLEMENT_023`(409)。

競合は contract version と idempotency により直列化する。upgrade `PENDING_PAYMENT` 中は解約/downgrade/別 change を409、解約予約中は撤回まで change を409、downgrade予約中の解約は schedule を取消してから解約予約を作る。subscription/customer を Update API で付替えない。

## 4. 状態・Webhook

```
PENDING --invoice.paid--> ACTIVE
PENDING --checkout.session.expired--> CANCELLED
ACTIVE --upgrade requested--> ACTIVE + change:PENDING_PAYMENT
change:PENDING_PAYMENT --invoice.paid--> APPLIED（新権利を原子的に発行）
change:PENDING_PAYMENT --invoice.payment_failed/expired--> FAILED（旧権利維持）
ACTIVE --downgrade scheduled--> ACTIVE + change:SCHEDULED
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
| `customer.subscription.updated` | subscription ref、customer ref、最新 Subscription を再取得 | anchor、cancel、schedule、period を単調に同期。downgrade は target Stripe Price・schedule phase・effectiveAt 到達が全て一致した場合だけ APPLIED |
| `customer.subscription.pending_update_applied` | `stripe_invoice_ref` + `stripe_subscription_ref` + target snapshot が一致する PENDING_PAYMENT change | `invoice.paid` 済みであることを再確認して upgrade を APPLIED |
| `customer.subscription.pending_update_expired` | 同じ3要素で PENDING_PAYMENT change を解決 | change を FAILED、旧プラン/権利を維持 |
| `customer.subscription.deleted` | contract の最新 object と period end | 有償期末解約を EXPIRED、pointer削除、権利失効 |
| `subscription_schedule.updated/released/canceled/completed` | change.schedule ref、customer/subscription/scope | schedule の補助同期、失敗/終了又は migration saga を遷移。無期限downgradeの APPLIED 判定には使わない |

`pending_update` は Stripe の独立 ID ではないため、`stripe_invoice_ref` + `stripe_subscription_ref` + `pending_update_expires_at` + `pending_update_target_snapshot` で変更多重度を特定する。event の `created` と取得した Stripe object の更新時刻/状態を比較し、古い event で現状態を戻さない。処理失敗は既存 event 表の `attempt_count/failed_at/processed_at` を更新して指数 backoff、上限後は照合キューへ送る。重複 event は副作用なしで 200、署名不正は 400、内部失敗は 5xx として Stripe 再送を受ける。

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
 id BINARY(16) NOT NULL COMMENT 'UUIDv7', product_kind VARCHAR(8) NOT NULL, product_key VARCHAR(64) NOT NULL, scope_kind VARCHAR(8) NOT NULL,
 organization_id BIGINT UNSIGNED NULL, stripe_price_ref VARCHAR(255) NOT NULL, currency CHAR(3) NOT NULL DEFAULT 'JPY',
 amount_excluding_tax BIGINT NOT NULL, tax_rate_basis_points INT NOT NULL, tax_name_snapshot VARCHAR(64) NOT NULL,
 is_included_in_price BOOLEAN NOT NULL, amount_including_tax BIGINT NOT NULL, effective_from DATETIME(6) NOT NULL,
 effective_until DATETIME(6) NULL, version BIGINT NOT NULL, created_by BIGINT UNSIGNED NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), deleted_at DATETIME(6) NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_bpv_stripe_price(stripe_price_ref), UNIQUE KEY uk_bpv_version(product_kind,product_key,scope_kind,version), KEY idx_bpv_sale(product_kind,product_key,scope_kind,effective_from,effective_until), KEY idx_bpv_org(organization_id),
 CONSTRAINT chk_bpv_kind CHECK(product_kind IN ('PLAN','ADDON')), CONSTRAINT chk_bpv_scope CHECK(scope_kind IN ('USER','TEAM','ORG')), CONSTRAINT chk_bpv_currency CHECK(currency='JPY'),
 CONSTRAINT chk_bpv_tax CHECK(tax_rate_basis_points BETWEEN 0 AND 10000), CONSTRAINT chk_bpv_amount CHECK(amount_including_tax>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='不変の販売価格/Stripe Price正本';

CREATE TABLE billing_contract_changes (
 id BINARY(16) NOT NULL COMMENT 'UUIDv7', contract_id BINARY(16) NOT NULL, billing_customer_id BINARY(16) NOT NULL,
 organization_id BIGINT UNSIGNED NULL, kind VARCHAR(16) NOT NULL, status VARCHAR(24) NOT NULL, from_plan_key VARCHAR(64) NOT NULL, to_plan_key VARCHAR(64) NOT NULL,
 from_price_version_id BINARY(16) NOT NULL, to_price_version_id BINARY(16) NOT NULL, from_amount_including_tax BIGINT NOT NULL, to_amount_including_tax BIGINT NOT NULL,
 stripe_invoice_ref VARCHAR(255) NULL, stripe_subscription_ref VARCHAR(255) NULL, pending_update_expires_at DATETIME(6) NULL, pending_update_target_snapshot JSON NULL, stripe_schedule_ref VARCHAR(255) NULL,
 effective_at DATETIME(6) NOT NULL, expires_at DATETIME(6) NULL, idempotency_key CHAR(36) NOT NULL, request_hash CHAR(64) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 created_by BIGINT UNSIGNED NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), deleted_at DATETIME(6) NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_bcc_idempotency(contract_id,idempotency_key), UNIQUE KEY uk_bcc_invoice(stripe_invoice_ref), UNIQUE KEY uk_bcc_schedule(stripe_schedule_ref),
 KEY idx_bcc_contract_status(contract_id,status,effective_at), KEY idx_bcc_org(organization_id),
 CONSTRAINT fk_bcc_contract FOREIGN KEY(contract_id) REFERENCES billing_contracts(id), CONSTRAINT fk_bcc_customer FOREIGN KEY(billing_customer_id) REFERENCES billing_customers(id),
 CONSTRAINT fk_bcc_from_price FOREIGN KEY(from_price_version_id) REFERENCES billing_price_versions(id), CONSTRAINT fk_bcc_to_price FOREIGN KEY(to_price_version_id) REFERENCES billing_price_versions(id),
 CONSTRAINT chk_bcc_kind CHECK(kind IN ('UPGRADE','DOWNGRADE')), CONSTRAINT chk_bcc_status CHECK(status IN ('PENDING_PAYMENT','SCHEDULED','APPLIED','FAILED','CANCELLED')),
 CONSTRAINT chk_bcc_refs CHECK((kind='UPGRADE' AND stripe_schedule_ref IS NULL) OR (kind='DOWNGRADE' AND stripe_schedule_ref IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='プラン変更Saga';

CREATE TABLE active_billing_change_pointers (
 contract_id BINARY(16) NOT NULL, change_id BINARY(16) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 PRIMARY KEY(contract_id), UNIQUE KEY uk_abcp_change(change_id),
 CONSTRAINT fk_abcp_contract FOREIGN KEY(contract_id) REFERENCES billing_contracts(id), CONSTRAINT fk_abcp_change FOREIGN KEY(change_id) REFERENCES billing_contract_changes(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='進行中プラン変更ポインタ（active_contract_pointersと同じ現在状態例外、UUID列は親のものを参照）';

CREATE TABLE billing_customer_migrations (
 id BINARY(16) NOT NULL COMMENT 'UUIDv7', contract_id BINARY(16) NOT NULL, billing_customer_id BINARY(16) NOT NULL,
 organization_id BIGINT UNSIGNED NULL, legacy_psp_customer_ref VARCHAR(255) NOT NULL, legacy_psp_subscription_ref VARCHAR(255) NOT NULL,
 stripe_setup_intent_ref VARCHAR(255) NULL, stripe_schedule_ref VARCHAR(255) NULL, effective_at DATETIME(6) NOT NULL, status VARCHAR(32) NOT NULL,
 compensation_reason VARCHAR(500) NULL, idempotency_key CHAR(36) NOT NULL, version BIGINT NOT NULL DEFAULT 0, created_by BIGINT UNSIGNED NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), deleted_at DATETIME(6) NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_bcm_contract(contract_id), UNIQUE KEY uk_bcm_setup(stripe_setup_intent_ref), UNIQUE KEY uk_bcm_schedule(stripe_schedule_ref), UNIQUE KEY uk_bcm_idempotency(contract_id,idempotency_key), KEY idx_bcm_status(status,effective_at), KEY idx_bcm_org(organization_id),
 CONSTRAINT fk_bcm_contract FOREIGN KEY(contract_id) REFERENCES billing_contracts(id), CONSTRAINT fk_bcm_customer FOREIGN KEY(billing_customer_id) REFERENCES billing_customers(id),
 CONSTRAINT chk_bcm_status CHECK(status IN ('CREATED','PAYMENT_METHOD_COLLECTING','SCHEDULE_CREATED','OLD_CANCEL_SCHEDULED','COMPLETED','COMPENSATING','COMPENSATED','FAILED'))
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

CREATE TABLE billing_invoice_lines (
 id BINARY(16) NOT NULL COMMENT 'UUIDv7', invoice_id BINARY(16) NOT NULL, organization_id BIGINT UNSIGNED NULL, psp_line_ref VARCHAR(255) NOT NULL, description_snapshot VARCHAR(500) NOT NULL,
 quantity DECIMAL(12,3) NOT NULL DEFAULT 1, amount_excluding_tax BIGINT NOT NULL, discount_amount BIGINT NOT NULL DEFAULT 0, tax_name_snapshot VARCHAR(64) NULL, tax_rate_basis_points INT NULL,
 tax_amount BIGINT NOT NULL DEFAULT 0, is_included_in_price BOOLEAN NOT NULL, amount_including_tax BIGINT NOT NULL, period_start DATETIME(6) NULL, period_end DATETIME(6) NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id), UNIQUE KEY uk_bil_line(invoice_id,psp_line_ref), KEY idx_bil_org(organization_id), CONSTRAINT fk_bil_invoice FOREIGN KEY(invoice_id) REFERENCES billing_invoices(id), CONSTRAINT chk_bil_quantity CHECK(quantity>0), CONSTRAINT chk_bil_tax CHECK(tax_rate_basis_points IS NULL OR tax_rate_basis_points BETWEEN 0 AND 10000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='請求明細不変投影';

ALTER TABLE billing_contracts MODIFY COLUMN psp_customer_ref VARCHAR(255) NULL COMMENT 'Stripe Customer ID（履歴参照）',
 MODIFY COLUMN psp_subscription_ref VARCHAR(255) NULL COMMENT 'Stripe Subscription ID（webhook逆引き）',
 ADD COLUMN billing_customer_id BINARY(16) NULL AFTER psp_customer_ref,
 ADD COLUMN price_version_id BINARY(16) NULL AFTER price_jpy_snapshot, ADD COLUMN billing_cycle_anchor_at DATETIME(6) NULL,
 ADD COLUMN cancel_scheduled_at DATETIME(6) NULL, ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
 ADD KEY idx_bc_customer(billing_customer_id), ADD KEY idx_bc_price_version(price_version_id),
 ADD CONSTRAINT fk_bc_customer FOREIGN KEY(billing_customer_id) REFERENCES billing_customers(id),
 ADD CONSTRAINT fk_bc_price_version FOREIGN KEY(price_version_id) REFERENCES billing_price_versions(id);

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

-- ADMIN は既定付与。DEPUTY_ADMIN は is_default=0 の天井定義だけを持ち、
-- permission_groups 経由で当該 scope に明示付与された場合だけ実効化する。
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW() FROM roles r CROSS JOIN permissions p
WHERE r.name='ADMIN' AND p.name IN ('MANAGE_TEAM_BILLING','MANAGE_ORGANIZATION_BILLING')
  AND NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 0, NOW() FROM roles r CROSS JOIN permissions p
WHERE r.name='DEPUTY_ADMIN' AND p.name IN ('MANAGE_TEAM_BILLING','MANAGE_ORGANIZATION_BILLING')
  AND NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);

CREATE TABLE billing_api_idempotencies (
 id BINARY(16) NOT NULL COMMENT 'UUIDv7', actor_id BIGINT UNSIGNED NOT NULL, http_method VARCHAR(8) NOT NULL, request_path VARCHAR(255) NOT NULL,
 idempotency_key CHAR(36) NOT NULL, request_hash CHAR(64) NOT NULL, response_status SMALLINT NOT NULL, response_json JSON NOT NULL,
 expires_at DATETIME(6) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id), UNIQUE KEY uk_bai_actor_request(actor_id,http_method,request_path,idempotency_key), KEY idx_bai_expiry(expires_at),
 CONSTRAINT chk_bai_status CHECK(response_status BETWEEN 200 AND 599)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消費者変更APIの冪等応答';
```

価格は `plans.base_monthly_price_jpy` / inline `price_data` を販売正本にしない。V196 は PLAN/ADDON の既存有効額を `billing_price_versions(product_kind,product_key)` と既存 Stripe Price に backfill、NULL/不整合は販売停止にする。`billing_contract_changes` は **PLAN契約だけ**の変更Sagaであり、Service が from/to priceVersion の `product_kind=PLAN` を検証する。ADDON は各 `billing_contracts(contract_kind=ADDON,feature_key)` を独立にCheckoutし、期末取消は `cancel_at_period_end`/`valid_until=current_period_end`、`invoice.paid` と明細投影をPLANと同じ正本で処理する。Customer 作成は DB の `uk_bcu_scope` reservation（`PROVISIONING`）→Stripe Customer create（customerId由来Idempotency-Key）→ref保存+`ACTIVE` の Saga とする。Stripe失敗は `PROVISION_FAILED`（error code/attempt countを保存）として同一予約行だけを安全に再試行する。Stripe成功後のDB失敗は Customer metadata `orphaned=true` と照合キューへ送り、再実行時にmetadataで同じCustomerを回収して `ACTIVE` 化する。ACTIVE 以外のCustomerでCheckout/Portalを開始してはならない。V196 適用前に既存 `billing_contracts` のPSP参照長（各255以下）・非NULL subscription ref一意性・status/履歴件数を番人クエリで検証し、不適合ならALTERを開始しない。V151既存行を用いる migration IT で参照値、status、履歴件数が不変であることを観測する。

PLAN change は同一DB transactionで contract の version をCASし、`billing_contract_changes` INSERT と `active_billing_change_pointers` の contract_id PK reservation を確保してcommitする。commit後にだけ Stripe を `Idempotency-Key=billing-change-{changeId}` で呼ぶ。Stripe失敗は change をFAILEDへCASし pointerをDELETE、Stripe成功後のDB失敗は object metadata のchangeIdで再照合して補償する。`APPLIED`/`FAILED`/`CANCELLED` のterminal遷移は同一transactionでpointerをDELETEするため、同一contractの並行changeは常に1件だけ進行する。

## 6. legacy Customer 移行 Saga

TEAM/ORG の operator-owned subscription は Customer を付替えず、通常 Checkout `create_prorations` も使わない。

1. `billing_customer_migrations=CREATED` を作り、新 scope Customer を確保する。
2. 限定 Customer Portal 又は SetupIntent で新 Customer に支払方法を収集し、`PAYMENT_METHOD_COLLECTING`。旧個人 Customer のカード/請求先は読まない。
3. 成功後、`start_date=current_period_end` の **Stripe Subscription Schedule** を作る。開始前の invoice はゼロ、`SCHEDULE_CREATED`。
4. schedule 作成成功を永続化した後だけ旧 subscription を `cancel_at_period_end=true` にして `OLD_CANCEL_SCHEDULED`。
5. schedule start と新 invoice.paid を確認して `COMPLETED`。新 scope Customer の contract を正規所有者として切替える。
6. step 3〜5 の失敗は `COMPENSATING` とし、新 schedule を cancel/release、旧 cancel を `false` へ戻す。両方成功で `COMPENSATED`、戻せない場合だけ `FAILED`＋運営照合。旧 Customer/delete/detach はしない。

## 7. API 契約

公開価格は `GET /api/v1/public/billing/plans?scopeKind={USER|TEAM|ORG}`（認証不要、`scopeKind`必須、200）で返す。Controller に `@IntentionallyPublic("/api/v1/public/billing/plans")` を付け、SecurityConfig はこの **GETだけ**を exact `permitAll`、`PublicApiRateLimitFilter` はIPごと60回/分を適用する。応答は `data:{scopeKind,plans:PublicPlan[],addons:PublicAddon[]}`、`PublicPlan={planKey:string,displayName:string,description:string,startingMonthlyTotal:Money?,priceBands:PublicPriceBand[],quoteRequired:boolean,available:boolean,features:string[]}`、`PublicAddon={featureKey:string,displayName:string,description:string,startingMonthlyTotal:Money?,priceBands:PublicPriceBand[],quoteRequired:boolean,available:boolean}` とする。`PublicPriceBand={minMembers:int32,maxMembers:int32?,startingMonthlyTotal:Money?}`。USER は `startingMonthlyTotal` を表示できるが、人数が未確定のTEAM/ORG は確定額を表示せず、`quoteRequired=true` と「ログイン後に対象チーム/組織で確定見積り」を表示する。`available=false` のとき全価格はnull、Checkout/Stripe ref/個別 scope情報は返さない。全認証後 API は `BillingAccessGuard` を通す。

以下の表は特記なき限りすべて `/api/v1` prefix を省略している（例: `GET /me/billing/scopes` は `GET /api/v1/me/billing/scopes`）。

| API | request（型/必須） | response `data` | status |
|---|---|---|---|
| `GET /me/billing/scopes` | なし | `items: Scope[]{kind enum,id int64,name string,manage boolean}` | 200 |
| `GET /me/billing/summary` | `scopeKind enum`,`scopeId int64` | `scope`,`plan: Contract?`,`addons: Contract[]`,`nextInvoice: Money?`,`quoteWindow:{available,availableAt?}` | 200/403 |
| `GET /me/billing/invoices` | scope + `cursor:string?` + `size:int[1,100]=20` | `data: InvoiceSummary[]`, `meta:{nextCursor:string?,hasNext:boolean}` | 200/403 |
| `GET /me/billing/invoices/{id}` | UUIDv7 | `InvoiceDetail{lines[],issuer,billingAddress?,totals}` | 200/403/404 |
| `POST /me/billing/invoices/{id}/document-url` | `Idempotency-Key UUID` | `{url:https URL,expiresAt:datetime}` | 200/403/404/409/502 |
| `POST /me/billing/quotes` | header key; `{scopeKind,scopeId,priceVersionId:UUID}`（PLAN/ADDON双方） | `{quoteId:UUID,productKind:'PLAN'|'ADDON',initialTotal:Money,nextMonthlyTotal:Money,expiresAt,periodStart,periodEnd}` | 201/403/409 |
| `POST /me/billing/checkout-sessions` | header key; `{quoteId:UUID}` | `{checkoutUrl:https URL,expiresAt:datetime}` | 201/403/409/502 |
| `POST /me/billing/contracts/{id}/change-previews` | header key; `{toPriceVersionId:UUID,version:int64}` | `{previewToken:opaque>=32,kind,amountDueNow:Money,effectiveAt,expiresAt}` | 201/403/404/409 |
| `POST /me/billing/contracts/{id}/changes` | header key; `{previewToken:string,version:int64}` | `{changeId:UUID,status enum,effectiveAt}` | 202/403/404/409/502 |
| `POST /me/billing/contracts/{id}/cancel` | header key; `{version:int64}` | `{scheduledAt,endAt,status:'SCHEDULED'}` | 200/403/404/409/502 |
| `DELETE /me/billing/contracts/{id}/cancel` | header key + `version` query int64 | `{endAt,status:'ACTIVE'}` | 200/403/404/409/502 |
| `POST /me/billing/portal-sessions` | header key; `{scopeKind,scopeId}` | `{url:https URL,expiresAt:datetime}` | 201/403/409/502 |
| `POST /webhooks/stripe` | Stripe raw body/signature | empty | 200/400/500 |

`Money={currency:'JPY',amountIncludingTax:int64,amountExcludingTax:int64,taxAmount:int64,taxName:string?,taxRateBasisPoints:int?}`。`TaxBreakdown={taxName:string?,taxRateBasisPoints:int?,taxAmount:int64}`。`Scope={kind:'USER'|'TEAM'|'ORG',id:int64,name:string,manage:boolean}`。`ContractBase={id:UUIDv7,status:'PENDING'|'ACTIVE'|'PAST_DUE'|'CANCELLED'|'EXPIRED',priceVersionId:UUIDv7?,currentPeriodEnd:datetime?,cancel:{scheduledAt:datetime,endAt:datetime}|null,canCancel:boolean,canResume:boolean,version:int64}`、`Contract=ContractBase & ({contractKind:'PLAN',planKey:string,featureKey:null}|{contractKind:'ADDON',planKey:null,featureKey:string})` とする。したがって `plan` は `contractKind='PLAN'` の `Contract` 又はnull、`addons` の各要素は `contractKind='ADDON'` の `Contract` のみであり、`planKey` と `featureKey` は必ず一方だけが非null（両null・両non-null・不一致 kind はfail-closed）である。各 Contract の `id` と `version` は cancel/resume request にそのまま渡し、`cancel`/`canCancel`/`canResume` で個別の期末取消・撤回可否と終了日を描画する。`InvoiceSummary={id:UUIDv7,status:'DRAFT'|'OPEN'|'PAID'|'UNCOLLECTIBLE'|'VOID',periodStart:datetime?,periodEnd:datetime?,total:Money,paidAt:datetime?}`。`InvoiceLine={description:string,quantity:decimal,amountExcludingTax:int64,discount:int64,taxes:TaxBreakdown[],amountIncludingTax:int64,periodStart:datetime?,periodEnd:datetime?}`。`Address={country:string,line1:string,city:string?,postalCode:string?}`。`InvoiceDetail=InvoiceSummary & {lines:InvoiceLine[],issuer:{name:string},billingAddress:Address?,subtotal:Money,discount:Money}`。すべての日時は ISO-8601 offset 付き、nullable は `?` のみ、ただし上記 XOR のキーは明示 `null` を返す。未知 enum は fail-closed とする。document/Portal URL は JSON の短命 URL に統一し 302 を使わない。preview token は random 256bit を hash 保存し、actor/scope/contract/version/request hash に束縛、一回だけ、`expiresAt`（最大10分）で無効化する。idempotency は `billing_api_idempotencies` の `actor_id,method,path,key` UNIQUE、同 key の hash 相違は409、同一は保存済 response を返す。

既存 `ENTITLEMENT_005`（scope forbidden/403）、`ENTITLEMENT_007`（contract not found/404）、`ENTITLEMENT_015`（Checkout失敗/502）、`ENTITLEMENT_016`（PENDING競合/409）を再利用する。新エラーは既存最大 `ENTITLEMENT_017` の次を実装開始時に再確認して予約する。現時点の案は `ENTITLEMENT_018`=invoice not found(404)、019=price not sellable(409)、020=preview expired(409)、021=change conflict(409)、022=month boundary(409)、023=quote expired(409)、024=migration required(409)、025=Stripe unavailable(502)。マージ時に最大+1を再確認し `GlobalExceptionHandler` へ明示登録する。

## 8. 税、保持、監査、非機能

Stripe invoice/line を正本として金額を取得し、`subtotal - discount + tax = total`、各 line の税込/税抜/端数合計が invoice と一致しないと投影を確定しない。JPY は小数なし、Stripe の line amount を再丸めしない。invoice/line は割引、税名、税率、`is_included_in_price`、発行者/請求先 snapshot を不変保存し、bearer URL snapshot 列は持たない。請求書投影は7年保持し、Webhook raw payloadは永続化しない。

`BILLING_CHECKOUT_CREATED`、`BILLING_CHANGE_*`、`BILLING_CANCEL_*`、`BILLING_PORTAL_OPENED`、`BILLING_INVOICE_VIEWED`、`BILLING_MIGRATION_*`、`BILLING_OPERATOR_VIEWED`、webhook成功/失敗を actor/scope/object ref/金額で監査する（カード番号、住所全文、URL、payloadは除外）。一覧 P95 は500ms、cursor既定20最大100、表示では Stripe 同期呼出しをせず投影を読む。rate limit は scope ごとに checkout/change/cancel/Portal 各10回/時、CSP/ログ/例外はPIIを出さない。

## 9. テストと受入条件

| AC | ケース（種別/観測点） |
|---|---|
| BC-01 | 正常/外部失敗・結合: USER/TEAM/ORG 各 scope は`PROVISIONING`予約を一意に確保し、Stripe成功後だけref+ACTIVE。失敗はPROVISION_FAILED、orphan回収/再試行後に一意ACTIVE、非ACTIVEではCheckout/Portal不可 |
| BC-02 | 認可・結合: V196でTEAM ADMINには`MANAGE_TEAM_BILLING`、ORG ADMINには`MANAGE_ORGANIZATION_BILLING`だけを既定付与。DEPUTY/MEMBERには未付与、対応キーを明示付与したDEPUTYだけを許可し取消後は即403。SYSTEM_ADMIN は消費者 invoice/Portal 403、他scope id は404 |
| BC-03 | 境界・単体: `nextMonthStart-30分-1秒` は作成可、`-30分` ちょうど以後は `ENTITLEMENT_022`、月初直後は再作成可。quote/session同一 `nextMonthStart-1秒`、うるう年/年跨ぎも観測 |
| BC-04 | 正常/外部失敗・Stripe fixture: 新規/upgrade は `invoice.paid` 前にACTIVE/権利なし、payment_failedで旧プラン維持 |
| BC-05 | 正常・Stripe test clock: downgrade schedule が翌月1日まで権利/金額を変えず、target Price/schedule phase/effectiveAt一致の`customer.subscription.updated`で一回だけ切替。ADDON新規/期末取消も同じinvoice/valid_until正本 |
| BC-06 | 境界・結合: 解約の前/ちょうど/後、撤回可否、`deleted`後EXPIRED/pointer削除/権利失効 |
| BC-07 | 並行・結合: 同一/異なる idempotency key、version競合、重複/順不同 webhook で二重契約・監査・請求なし。`invoice.paid` が `checkout.session.completed` より先でも subscription metadata から同一contractを解決し一回だけACTIVE化 |
| BC-07b | 並行/補償・結合: `active_billing_change_pointers` のcontract PKで同時changeは1件だけ。Stripe失敗/terminal遷移でpointer削除、CAS競合とDB commit後Stripe失敗をmetadata再照合で補償 |
| BC-08 | legacy補償・E2E+fixture: SetupIntent→future Schedule→旧cancelの順、schedule失敗/旧cancel失敗でschedule取消・旧cancel撤回 |
| BC-09 | 税・結合: 税込主表示、割引/税/端数/請求先snapshotがStripe invoiceと一致、Tax未登録ではautomatic_taxなし |
| BC-10 | UI/E2E: `/pricing`、既存導線文脈、全scope切替、月別明細/document URL、PLANと有償ADDONの個別表示、各ADDONの期末取消/撤回、解約一確認/撤回、6言語キー解決 |
| BC-11 | 公開・Security IT: 未認証 `GET /api/v1/public/billing/plans` は200、他methodは認証必須、`@IntentionallyPublic` とSecurityConfig exact GETが番人で一致、IP 61回目は429 |

Flyway upgrade、全新表を含むE2E truncate、OpenAPI再生成、frontend生成型、backend/FE unit・integration・E2EをCI観測点とする。

## 10. ロールアウト/ロールバック

`read-only hub → price versions → scope Customer新規契約 → invoice投影 → Portal → cancel/resume → change → legacy migration` の順に feature flag で有効化する。各段で webhook遅延、Stripe/DB invoice差分、二重請求ゼロを監視する。異常時は新規作成 flag のみOFFにし、既存 Subscription/Webhook を止めない。作成済 Customer/invoice/audit を削除せず、legacy read path は全migration完了と照合後にだけ削除する。
