package com.mannschaft.app.billing.invoice;

import com.mannschaft.app.billing.ActiveBillingContractOperationPointerEntity;
import com.mannschaft.app.billing.ActiveBillingContractOperationPointerRepository;
import com.mannschaft.app.billing.BillingContractChangeEntity;
import com.mannschaft.app.billing.BillingContractChangeKind;
import com.mannschaft.app.billing.BillingContractChangeRepository;
import com.mannschaft.app.billing.BillingContractChangeStatus;
import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractOperationEntity;
import com.mannschaft.app.billing.BillingContractOperationRepository;
import com.mannschaft.app.billing.BillingOperationActorKind;
import com.mannschaft.app.billing.BillingOperationKind;
import com.mannschaft.app.billing.BillingOperationStatus;
import com.mannschaft.app.billing.BillingOperationStep;
import com.mannschaft.app.billing.BillingPaymentGateway;
import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceCreationSource;
import com.mannschaft.app.billing.BillingPriceVersionEntity;
import com.mannschaft.app.billing.BillingPriceVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.BillingTaxBehavior;
import com.mannschaft.app.billing.ContractStatus;
import com.mannschaft.app.billing.EntitlementScopeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * Billing Center PR6b-1 — B群 確定の原子性と invoice 先着（AC-37〜45・試練 red）。
 *
 * <h2>なぜ webhook 経路で書くか</h2>
 * <p>AC-37〜41 は「change・operation・権利・pointer が<b>同一トランザクション</b>」であることの検証であり、
 * モックで例外を投げても tx の巻き戻りは再現できない（{@code try/catch} に握られる経路では
 * そもそも例外が外へ出ない）。実 DB の一意制約で狙った UPDATE を落とし、<b>コミット後の DB の姿</b>で
 * 測る。そのため本クラスに {@code @Transactional} は付けない（付けるとコミットが起きず偽の緑になる）。</p>
 *
 * <h2>AC-44/AC-45 は実在の欠陥に対する red</h2>
 * <p>現行 {@code BillingSubscriptionWebhookService#applyContractTransition} は
 * subscription ref の逆引きだけで {@code markContractPastDue} / {@code extendContractPeriod} を呼ぶ。
 * upgrade の差額請求が失敗しただけで契約が {@code PAST_DUE} に落ち、差額の支払いで契約期間が
 * 延長されてしまう。陽性対照（通常の更新請求では従来どおり遷移する）を対で置いてある。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6b-1 upgrade の確定と invoice webhook（B群 AC-37〜45・試練 red）")
class BillingPlanUpgradeConfirmWebhookRedIT extends AbstractBillingInvoiceWebhookIT {

    private static final String UPGRADE_INVOICE_REF = "in_pr6b1_upgrade";
    private static final String RENEWAL_INVOICE_REF = "in_pr6b1_renewal";
    private static final String TO_PLAN_KEY = "FULL";
    private static final LocalDateTime SEEDED_PERIOD_END = LocalDateTime.of(2026, 1, 15, 0, 0);
    /** invoice payload の {@code period_end}（2026-02-01）。延長されたかの観測点。 */
    private static final LocalDateTime INVOICE_PERIOD_END = LocalDateTime.of(2026, 2, 1, 0, 0);

    @Autowired private BillingContractChangeRepository changeRepository;
    @Autowired private BillingContractOperationRepository operationRepository;
    @Autowired private ActiveBillingContractOperationPointerRepository pointerRepository;
    @Autowired private BillingPriceVersionRepository priceVersionRepository;
    @Autowired private BillingPriceBandVersionRepository bandRepository;

    /** 回収（{@code customer.subscription.updated}）が実 Stripe を触らないようにする。 */
    @MockitoBean private BillingPaymentGateway billingPaymentGateway;

    private UUID fromBandId;
    private UUID toBandId;
    private UUID operationId;

    @BeforeEach
    void seedUpgradeInFlight() {
        jdbcTemplate.update("DELETE FROM billing_contract_changes");
        jdbcTemplate.update("DELETE FROM active_billing_contract_operation_pointers");
        jdbcTemplate.update("DELETE FROM billing_contract_operations");
        jdbcTemplate.update("DELETE FROM entitlements WHERE scope_id = ?", BILLING_SCOPE_ID);

        fromBandId = insertBand("BASIC", 1_200L);
        toBandId = insertBand(TO_PLAN_KEY, 3_300L);

        jdbcTemplate.update("UPDATE billing_contracts SET price_band_version_id = ? WHERE id = ?",
                uuidBytes(fromBandId), uuidBytes(billingContractId));

        operationId = insertOperation(billingContractId);
        insertPointer(billingContractId, operationId);
    }

    // ═════════ AC-37: paid 確定の原子性 ═════════

    @Test
    @DisplayName("AC-37: paid 確定で change APPLIED ＋ operation APPLIED ＋ 権利切替 ＋ pointer 削除が同一トランザクションで起きる")
    void AC37_paid確定は一括で反映される() throws Exception {
        UUID changeId = insertChange(UPGRADE_INVOICE_REF, BillingContractChangeStatus.PENDING_PAYMENT, null);

        postSigned(StripeWebhookPayloadFixture.event(
                "evt_pr6b1_ac37", "invoice.paid", upgradeInvoice(UPGRADE_INVOICE_REF, "paid")));

        assertThat(reloadChange(changeId).getStatus())
                .as("change は APPLIED").isEqualTo(BillingContractChangeStatus.APPLIED);
        assertThat(reloadOperation().getStatus())
                .as("operation も同じ tx で APPLIED").isEqualTo(BillingOperationStatus.APPLIED);
        BillingContractEntity contract = reloadContract();
        assertThat(contract.getPlanKey()).as("権利（プラン）が切り替わる").isEqualTo(TO_PLAN_KEY);
        assertThat(contract.getPriceBandVersionId()).as("band も target へ").isEqualTo(toBandId);
        assertThat(pointerRepository.findById(billingContractId))
                .as("pointer が削除され、次の mutation が可能になる").isEmpty();
    }

    // ═════════ AC-38 / AC-39 / AC-40: 失敗確定の3経路 ═════════

    @Test
    @DisplayName("AC-38: 確定失敗（decline）で change FAILED ＋ operation FAILED ＋ 旧権利維持 ＋ pointer 削除")
    void AC38_decline確定は失敗として一括反映() throws Exception {
        UUID changeId = insertChange(UPGRADE_INVOICE_REF, BillingContractChangeStatus.PENDING_PAYMENT,
                Instant.now().minus(1, ChronoUnit.HOURS));

        postSigned(StripeWebhookPayloadFixture.event(
                "evt_pr6b1_ac38", "invoice.payment_failed", upgradeInvoice(UPGRADE_INVOICE_REF, "open")));

        assertThat(reloadChange(changeId).getStatus()).isEqualTo(BillingContractChangeStatus.FAILED);
        assertThat(reloadOperation().getStatus()).isEqualTo(BillingOperationStatus.FAILED);
        assertThat(reloadContract().getPlanKey()).as("旧権利を維持する").isEqualTo("BASIC");
        assertThat(pointerRepository.findById(billingContractId)).as("pointer を解放する").isEmpty();
    }

    @Test
    @DisplayName("AC-39: invoice.voided でも change FAILED ＋ operation FAILED ＋ 旧権利維持 ＋ pointer 削除")
    void AC39_voidedでも失敗確定() throws Exception {
        UUID changeId = insertChange(UPGRADE_INVOICE_REF, BillingContractChangeStatus.REQUIRES_ACTION,
                Instant.now().plus(1, ChronoUnit.HOURS));

        postSigned(StripeWebhookPayloadFixture.event(
                "evt_pr6b1_ac39", "invoice.voided", upgradeInvoice(UPGRADE_INVOICE_REF, "void")));

        assertThat(reloadChange(changeId).getStatus()).isEqualTo(BillingContractChangeStatus.FAILED);
        assertThat(reloadOperation().getStatus()).isEqualTo(BillingOperationStatus.FAILED);
        assertThat(reloadContract().getPlanKey()).isEqualTo("BASIC");
        assertThat(pointerRepository.findById(billingContractId)).isEmpty();
    }

    @Test
    @DisplayName("AC-40: pending_update_expired でも change FAILED ＋ operation FAILED ＋ 旧権利維持 ＋ pointer 削除")
    void AC40_pending_update_expiredでも失敗確定() throws Exception {
        UUID changeId = insertChange(UPGRADE_INVOICE_REF, BillingContractChangeStatus.REQUIRES_ACTION,
                Instant.now().minus(1, ChronoUnit.MINUTES));

        postSigned(StripeWebhookPayloadFixture.event(
                "evt_pr6b1_ac40", "customer.subscription.pending_update_expired",
                subscriptionWithOperationMetadata()));

        assertThat(reloadChange(changeId).getStatus()).isEqualTo(BillingContractChangeStatus.FAILED);
        assertThat(reloadOperation().getStatus()).isEqualTo(BillingOperationStatus.FAILED);
        assertThat(reloadContract().getPlanKey()).isEqualTo("BASIC");
        assertThat(pointerRepository.findById(billingContractId)).isEmpty();
    }

    // ═════════ AC-41: 確定 tx の途中失敗 ═════════

    @Test
    @DisplayName("AC-41: 確定トランザクションが途中で失敗したら全て巻き戻り、pointer を残して再送・回収で拾える")
    void AC41_途中失敗はpointerを残して巻き戻る() throws Exception {
        // invoice ref をまだ握っていない change（AC-42 の先着経路）。
        UUID changeId = insertChange(null, BillingContractChangeStatus.PENDING_PAYMENT, null);
        given(billingPaymentGateway.findOperationIdOnSubscription(BILLING_SUBSCRIPTION_REF))
                .willReturn(Optional.of(operationId));
        // 同じ invoice ref を既に握っている別契約の行を置く。確定時の bind が uk_bcc_invoice で必ず落ちる。
        insertDecoyChangeHoldingInvoiceRef(UPGRADE_INVOICE_REF);

        try {
            postSigned(StripeWebhookPayloadFixture.event(
                    "evt_pr6b1_ac41", "invoice.paid", upgradeInvoice(UPGRADE_INVOICE_REF, "paid")));
        } catch (Exception expected) {
            // webhook は所有確定後の一時失敗を 5xx として上へ投げる（PR5 AC-13 の流儀）。ここでは握って続行する。
        }

        assertThat(reloadChange(changeId).getStatus())
                .as("途中失敗なら change の確定も巻き戻る（片方だけコミットされない）")
                .isEqualTo(BillingContractChangeStatus.PENDING_PAYMENT);
        assertThat(reloadOperation().getStatus())
                .as("operation も確定していない").isNotEqualTo(BillingOperationStatus.APPLIED);
        assertThat(reloadContract().getPlanKey()).as("権利も切り替わっていない").isEqualTo("BASIC");
        assertThat(pointerRepository.findById(billingContractId))
                .as("pointer を残すことで再送・回収が拾える（孤児にしない）").isPresent();
    }

    // ═════════ AC-42 / AC-43: invoice webhook が Stripe 応答より先着する窓 ═════════

    @Test
    @DisplayName("AC-42: invoice webhook が先着しても metadata の operationId から change を解決し invoice ref を一度だけ bind する")
    void AC42_先着invoiceはmetadataから解決される() throws Exception {
        UUID changeId = insertChange(null, BillingContractChangeStatus.PENDING_PAYMENT, null);
        given(billingPaymentGateway.findOperationIdOnSubscription(BILLING_SUBSCRIPTION_REF))
                .willReturn(Optional.of(operationId));

        postSigned(StripeWebhookPayloadFixture.event(
                "evt_pr6b1_ac42", "invoice.paid", upgradeInvoice(UPGRADE_INVOICE_REF, "paid")));

        BillingContractChangeEntity change = reloadChange(changeId);
        assertThat(change.getStripeInvoiceRef())
                .as("change 行作成時点では NULL だった invoice ref を bind する")
                .isEqualTo(UPGRADE_INVOICE_REF);
        assertThat(change.getStatus())
                .as("upgrade として処理され APPLIED になる").isEqualTo(BillingContractChangeStatus.APPLIED);

        // 二度目の再送で二重 bind・二重確定が起きない。
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_pr6b1_ac42b", "invoice.paid", upgradeInvoice(UPGRADE_INVOICE_REF, "paid")));
        assertThat(changeRepository.findByIdAndDeletedAtIsNull(changeId).orElseThrow().getStripeInvoiceRef())
                .isEqualTo(UPGRADE_INVOICE_REF);
    }

    @Test
    @DisplayName("AC-43: 先着した upgrade の invoice を通常の renewal 遷移へ流さない（契約期間を延長しない）")
    void AC43_先着invoiceで期間を延長しない() throws Exception {
        insertChange(null, BillingContractChangeStatus.PENDING_PAYMENT, null);
        given(billingPaymentGateway.findOperationIdOnSubscription(BILLING_SUBSCRIPTION_REF))
                .willReturn(Optional.of(operationId));

        postSigned(StripeWebhookPayloadFixture.event(
                "evt_pr6b1_ac43", "invoice.paid", upgradeInvoice(UPGRADE_INVOICE_REF, "paid")));

        assertThat(reloadContract().getCurrentPeriodEnd())
                .as("差額請求の paid で契約期間を延ばしてはならない")
                .isEqualTo(SEEDED_PERIOD_END);
    }

    // ═════════ AC-44 / AC-45: E8 契約本体の状態遷移に流用しない ═════════

    @Test
    @DisplayName("AC-44: change の invoice の payment_failed は契約を PAST_DUE にしない")
    void AC44_差額請求の失敗で契約をPAST_DUEにしない() throws Exception {
        insertChange(UPGRADE_INVOICE_REF, BillingContractChangeStatus.REQUIRES_ACTION,
                Instant.now().plus(1, ChronoUnit.HOURS));

        postSigned(StripeWebhookPayloadFixture.event(
                "evt_pr6b1_ac44", "invoice.payment_failed", upgradeInvoice(UPGRADE_INVOICE_REF, "open")));

        assertThat(reloadContract().getStatus())
                .as("旧プランの支払いは滞っていない。契約は ACTIVE のまま")
                .isEqualTo(ContractStatus.ACTIVE);
    }

    @Test
    @DisplayName("AC-44(陽性対照): 通常の更新請求の payment_failed は従来どおり契約を PAST_DUE にする")
    void AC44b_陽性対照_通常請求では従来どおりPAST_DUE() throws Exception {
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_pr6b1_ac44b", "invoice.payment_failed", renewalInvoice(RENEWAL_INVOICE_REF, "open")));

        assertThat(reloadContract().getStatus())
                .as("PR5 の挙動を壊していない（遮断が広すぎないことの対照）")
                .isEqualTo(ContractStatus.PAST_DUE);
    }

    @Test
    @DisplayName("AC-45: change の invoice の paid は extendContractPeriod を呼ばない（期間を延長しない）")
    void AC45_差額請求のpaidで期間を延長しない() throws Exception {
        insertChange(UPGRADE_INVOICE_REF, BillingContractChangeStatus.PENDING_PAYMENT, null);

        postSigned(StripeWebhookPayloadFixture.event(
                "evt_pr6b1_ac45", "invoice.paid", upgradeInvoice(UPGRADE_INVOICE_REF, "paid")));

        assertThat(reloadContract().getCurrentPeriodEnd())
                .as("差額請求は期間を動かさない").isEqualTo(SEEDED_PERIOD_END);
    }

    @Test
    @DisplayName("AC-45(陽性対照): 通常の更新請求の paid は従来どおり契約期間を延長する")
    void AC45b_陽性対照_通常請求では従来どおり延長する() throws Exception {
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_pr6b1_ac45b", "invoice.paid", renewalInvoice(RENEWAL_INVOICE_REF, "paid")));

        assertThat(reloadContract().getCurrentPeriodEnd())
                .as("PR5 の更新経路を壊していない").isEqualTo(INVOICE_PERIOD_END);
    }

    // ════════════════════════════════════════════════
    // フィクスチャ / ヘルパ
    // ════════════════════════════════════════════════

    private String upgradeInvoice(String invoiceRef, String status) {
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_" + invoiceRef, "プラン変更差額", 1L, 2_100L, 0L, 190L, true, 1000);
        return StripeWebhookPayloadFixture.invoiceObject(
                invoiceRef, BILLING_CUSTOMER_REF, BILLING_SUBSCRIPTION_REF, status,
                "jpy", 2_100L, 0L, 190L, 2_290L, line);
    }

    private String renewalInvoice(String invoiceRef, String status) {
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_" + invoiceRef, "BASIC プラン", 1L, 1_090L, 0L, 110L, false, 1000);
        return StripeWebhookPayloadFixture.invoiceObject(
                invoiceRef, BILLING_CUSTOMER_REF, BILLING_SUBSCRIPTION_REF, status,
                "jpy", 1_090L, 0L, 110L, 1_200L, line);
    }

    /** {@code metadata.billingOperationId} を載せた subscription オブジェクト（AC-40）。 */
    private String subscriptionWithOperationMetadata() {
        return """
                {"id":"%s","object":"subscription","customer":"%s","status":"active",
                 "current_period_start":1767225600,"current_period_end":1769904000,
                 "cancel_at_period_end":false,"created":1767225600,"livemode":false,
                 "metadata":{"billingOperationId":"%s"},
                 "items":{"object":"list","has_more":false,"url":"/v1/subscription_items","data":[]}}"""
                .formatted(BILLING_SUBSCRIPTION_REF, BILLING_CUSTOMER_REF, operationId);
    }

    private UUID insertBand(String productKey, long amountIncludingTax) {
        Instant now = Instant.now();
        BillingPriceVersionEntity version = priceVersionRepository.saveAndFlush(
                BillingPriceVersionEntity.builder()
                        .productKind(BillingProductKind.PLAN)
                        .productKey(productKey)
                        .scopeKind(EntitlementScopeKind.USER)
                        .catalogRevision("pr6b1-wh-" + productKey + "-" + System.nanoTime())
                        .revisionNo(System.nanoTime())
                        .status(BillingPriceVersionStatus.ACTIVE)
                        .provisionAttempts(0)
                        .effectiveFrom(now.minus(30, ChronoUnit.DAYS))
                        .lockVersion(0L)
                        .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL)
                        .createdAt(now)
                        .build());
        long excluding = Math.round(amountIncludingTax / 1.1d);
        return bandRepository.saveAndFlush(BillingPriceBandVersionEntity.builder()
                .productKind(BillingProductKind.PLAN)
                .productKey(productKey)
                .scopeKind(EntitlementScopeKind.USER)
                .bandNo(1).minMembers(1).maxMembers(null)
                .priceVersionId(version.getId())
                .stripePriceRef("price_pr6b1_wh_" + productKey)
                .currency("JPY")
                .inputAmount(amountIncludingTax)
                .taxBehavior(BillingTaxBehavior.INCLUSIVE)
                .taxCodeSnapshot("txcd_10000000")
                .taxMasterSnapshot("{\"name\":\"消費税\",\"rateBasisPoints\":1000}")
                .amountExcludingTax(excluding)
                .taxAmount(amountIncludingTax - excluding)
                .taxRateBasisPoints(1_000)
                .taxNameSnapshot("消費税")
                .includedInPrice(true)
                .amountIncludingTax(amountIncludingTax)
                .effectiveFrom(now.minus(30, ChronoUnit.DAYS))
                .status(BillingPriceVersionStatus.ACTIVE)
                .lockVersion(0L)
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL)
                .createdAt(now)
                .build()).getId();
    }

    private UUID insertOperation(UUID contractId) {
        return operationRepository.saveAndFlush(BillingContractOperationEntity.builder()
                .contractId(contractId)
                .billingCustomerId(billingCustomerId)
                .kind(BillingOperationKind.PLAN_CHANGE)
                .status(BillingOperationStatus.CALLING_STRIPE)
                .step(BillingOperationStep.STRIPE_APPLY_PLAN_CHANGE)
                .idempotencyKey(UUID.randomUUID().toString())
                .requestHash("a".repeat(64))
                .stripeSubscriptionRef(BILLING_SUBSCRIPTION_REF)
                .actorKind(BillingOperationActorKind.USER)
                .createdBy(BILLING_SCOPE_ID)
                .version(0L)
                .build()).getId();
    }

    private void insertPointer(UUID contractId, UUID opId) {
        pointerRepository.saveAndFlush(ActiveBillingContractOperationPointerEntity.builder()
                .contractId(contractId)
                .operationId(opId)
                .build());
    }

    private UUID insertChange(String invoiceRef, BillingContractChangeStatus status,
                              Instant pendingUpdateExpiresAt) {
        return changeRepository.saveAndFlush(newChange(billingContractId, operationId, invoiceRef, status,
                pendingUpdateExpiresAt)).getId();
    }

    /** AC-41: 同じ invoice ref を既に握っている別契約の行（{@code uk_bcc_invoice} 衝突の種）。 */
    private void insertDecoyChangeHoldingInvoiceRef(String invoiceRef) {
        long decoyScopeId = BILLING_SCOPE_ID + 1L;
        jdbcTemplate.update("DELETE FROM billing_contracts WHERE scope_id = ?", decoyScopeId);
        BillingContractEntity decoyContract = billingContractRepository.saveAndFlush(
                BillingContractEntity.builder()
                        .scopeKind(EntitlementScopeKind.USER).scopeId(decoyScopeId)
                        .contractKind(com.mannschaft.app.billing.ContractKind.PLAN).planKey("BASIC")
                        .status(ContractStatus.ACTIVE)
                        .billingCustomerId(billingCustomerId)
                        .pspCustomerRef(BILLING_CUSTOMER_REF)
                        .pspSubscriptionRef("sub_pr6b1_decoy")
                        .version(0L)
                        .contractedAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                        .build());
        UUID decoyOperationId = insertOperation(decoyContract.getId());
        changeRepository.saveAndFlush(newChange(decoyContract.getId(), decoyOperationId, invoiceRef,
                BillingContractChangeStatus.APPLIED, null));
    }

    private BillingContractChangeEntity newChange(UUID contractId, UUID opId, String invoiceRef,
                                                  BillingContractChangeStatus status,
                                                  Instant pendingUpdateExpiresAt) {
        return BillingContractChangeEntity.builder()
                .operationId(opId)
                .contractId(contractId)
                .billingCustomerId(billingCustomerId)
                .kind(BillingContractChangeKind.UPGRADE)
                .status(status)
                .fromPlanKey("BASIC")
                .toPlanKey(TO_PLAN_KEY)
                .fromPriceBandVersionId(fromBandId)
                .toPriceBandVersionId(toBandId)
                .fromAmountIncludingTax(1_200L)
                .toAmountIncludingTax(3_300L)
                .stripeInvoiceRef(invoiceRef)
                .stripeSubscriptionRef(BILLING_SUBSCRIPTION_REF)
                .pendingUpdateExpiresAt(pendingUpdateExpiresAt)
                .pendingUpdateTargetSnapshot(pendingUpdateExpiresAt == null
                        ? null : "{\"priceRef\":\"price_pr6b1_wh_FULL\"}")
                .effectiveAt(Instant.now())
                .idempotencyKey(opId.toString())
                .requestHash("b".repeat(64))
                .version(0L)
                .createdBy(BILLING_SCOPE_ID)
                .build();
    }

    private BillingContractChangeEntity reloadChange(UUID changeId) {
        return changeRepository.findByIdAndDeletedAtIsNull(changeId)
                .orElseThrow(() -> new AssertionError("change 行が消えている: " + changeId));
    }

    private BillingContractOperationEntity reloadOperation() {
        return operationRepository.findByIdAndDeletedAtIsNull(operationId)
                .orElseThrow(() -> new AssertionError("operation が消えている: " + operationId));
    }

    private BillingContractEntity reloadContract() {
        return billingContractRepository.findByIdAndDeletedAtIsNull(billingContractId).orElseThrow();
    }

    private static byte[] uuidBytes(UUID uuid) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }
}
