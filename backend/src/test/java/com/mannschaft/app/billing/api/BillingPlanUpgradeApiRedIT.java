package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.billing.BillingContractChangeEntity;
import com.mannschaft.app.billing.BillingContractChangeKind;
import com.mannschaft.app.billing.BillingContractChangeStatus;
import com.mannschaft.app.billing.BillingContractOperationEntity;
import com.mannschaft.app.billing.BillingContractOperationSagaService;
import com.mannschaft.app.billing.BillingOperationKind;
import com.mannschaft.app.billing.BillingOperationStatus;
import com.mannschaft.app.billing.BillingOperationStep;
import com.mannschaft.app.billing.BillingPlanChangeGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Billing Center PR6b-1 — B群 upgrade の実行（AC-25〜36 / AC-46 / AC-47・試練 red）。
 *
 * <p>確定（paid / failed / voided / pending_update_expired）の原子性は
 * {@code BillingPlanUpgradeConfirmAtomicityRedIT}（webhook 経路）が担う。ここでは
 * <b>API を叩いた瞬間に何が起きるか</b>だけを測る。</p>
 *
 * <p><b>E6' の4検体を個別に固定する</b>（AC-32/33/34/35）。Stripe の {@code pending_if_incomplete} は
 * <b>成功時は即時適用し pending_update を返さない</b>（失敗時のみ返る）。この向きを取り違えると
 * 「同期成功でも pending_update がある前提」の実装ができあがるため、4本を別々の検体で縛る。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6b-1 upgrade 実行 API（B群 AC-25〜47・試練 red）")
class BillingPlanUpgradeApiRedIT extends AbstractBillingPlanChangeApiIT {

    private static final String INVOICE_REF = "in_pr6b1_upgrade";

    @BeforeEach
    void setUp() {
        seedUpgradableContract("upgrade");
        insertEntitlement(contractId, FEATURE_KEY);
        stubStripeApply(INVOICE_REF, "open", false);
    }

    @AfterEach
    void tearDown() {
        cleanupScope();
    }

    // ═════════ AC-25: 応答の形 ═════════

    @Test
    @DisplayName("AC-25: changes は previewId 必須・202 で changeId/status/effectiveAt を返し clientSecret を返さない")
    void AC25_202でchangeIdとstatusを返しclientSecretは返さない() throws Exception {
        UUID previewId = createPreviewId();

        MvcResult result = change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.changeId").isNotEmpty())
                .andExpect(jsonPath("$.data.status").isNotEmpty())
                .andExpect(jsonPath("$.data.effectiveAt").isNotEmpty())
                .andReturn();

        String raw = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(raw)
                .as("clientSecret は payment-action だけが返す（AC-25）。本文のどこにも出してはならない")
                .doesNotContain("clientSecret").doesNotContain("client_secret").doesNotContain("_secret_");

        // previewId 欠落は 400（Spring の bean validation）。無条件 202 を排除する陽性対照。
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(String.format(CHANGES_PATH, contractId))
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.user(String.valueOf(userId)))
                        .header("Idempotency-Key", newKey())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + contractVersion() + "}"))
                .andExpect(status().isBadRequest());
    }

    // ═════════ AC-26 / AC-27 / AC-28: Saga と行の作られ方 ═════════

    @Test
    @DisplayName("AC-26: PR6a の Saga で予約する（operation=PLAN_CHANGE / CALLING_STRIPE / pointer 保持）")
    void AC26_Sagaで予約する() throws Exception {
        UUID previewId = createPreviewId();

        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isAccepted());

        assertThat(operationCount()).as("operation は1件だけ").isEqualTo(1L);
        BillingContractOperationEntity operation = operationOf(requireSingleChange().getOperationId());
        assertThat(operation.getKind()).isEqualTo(BillingOperationKind.PLAN_CHANGE);
        assertThat(operation.getStatus())
                .as("E1F: 決着まで CALLING_STRIPE のまま（支払い待ちの間も pointer を保持する）")
                .isEqualTo(BillingOperationStatus.CALLING_STRIPE);
        assertThat(operation.getStep()).isEqualTo(BillingOperationStep.STRIPE_APPLY_PLAN_CHANGE);
        assertThat(pointerCount()).as("耐久 lease は pointer ただ一つ（正本 05:285）").isEqualTo(1L);
    }

    @Test
    @DisplayName("AC-27: billing_contract_changes が operation と対で作られ operation_id で一意に参照する")
    void AC27_変更行はoperationと1対1() throws Exception {
        UUID previewId = createPreviewId();

        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isAccepted());

        BillingContractChangeEntity row = requireSingleChange();
        assertThat(row.getOperationId()).as("uk_bcc_operation で一意").isNotNull();
        assertThat(operationOf(row.getOperationId()).getContractId()).isEqualTo(contractId);
        assertThat(row.getKind()).isEqualTo(BillingContractChangeKind.UPGRADE);
        assertThat(row.getFromPlanKey()).isEqualTo(FROM_PLAN_KEY);
        assertThat(row.getToPlanKey()).isEqualTo(TO_PLAN_KEY);
        assertThat(row.getFromPriceBandVersionId()).isEqualTo(fromBandId);
        assertThat(row.getToPriceBandVersionId()).isEqualTo(toBandId);
        assertThat(row.getStripeScheduleRef())
                .as("chk_bcc_refs: UPGRADE は schedule ref を持たない").isNull();
        assertThat(row.getCreatedBy()).isEqualTo(userId);
    }

    @Test
    @DisplayName("AC-28: idempotency_key には operationId（36文字）を格納する")
    void AC28_冪等キーはoperationId() throws Exception {
        UUID previewId = createPreviewId();

        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isAccepted());

        BillingContractChangeEntity row = requireSingleChange();
        assertThat(row.getIdempotencyKey())
                .as("CHAR(36) の列に HTTP ヘッダ値をそのまま入れない")
                .hasSize(36)
                .isEqualTo(row.getOperationId().toString());
    }

    @Test
    @DisplayName("AC-28: 長い Idempotency-Key ヘッダを送っても 500 にしない")
    void AC28b_長いヘッダで500にしない() throws Exception {
        UUID previewId = createPreviewId();
        String longKey = "k".repeat(200);

        MvcResult result = change(userId, contractId, previewId, contractVersion(), longKey).andReturn();

        assertThat(result.getResponse().getStatus())
                .as("400 で断るのは可。CHAR(36) 溢れで 500 にするのは不可")
                .isIn(202, 400);
        if (result.getResponse().getStatus() == 202) {
            assertThat(requireSingleChange().getIdempotencyKey()).hasSize(36);
        }
    }

    // ═════════ AC-29 / AC-30 / AC-31: Stripe の呼び方 ═════════

    @Test
    @DisplayName("AC-29/30/31: always_invoice ＋ pending_if_incomplete、冪等キーは billing-operation-{id}、metadata に operationId")
    void AC29to31_Stripeの呼び方を固定する() throws Exception {
        UUID previewId = createPreviewId();

        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isAccepted());

        assertThat(planChangeCalls("applyPlanChange")).as("Stripe へ1回だけ送る").isEqualTo(1L);
        BillingPlanChangeGateway.PlanChangeApplyCommand command = lastApplyCommand();
        UUID operationId = requireSingleChange().getOperationId();

        assertThat(command.prorationBehavior())
                .isEqualTo(BillingPlanChangeGateway.PRORATION_BEHAVIOR_ALWAYS_INVOICE);
        assertThat(command.paymentBehavior())
                .isEqualTo(BillingPlanChangeGateway.PAYMENT_BEHAVIOR_PENDING_IF_INCOMPLETE);
        assertThat(command.stripeIdempotencyKey())
                .isEqualTo(BillingContractOperationSagaService.stripeIdempotencyKeyOf(operationId));
        assertThat(command.metadata())
                .as("PR6a の回収が痕跡照合に使うキーを焼き付ける")
                .containsEntry(BillingPlanChangeGateway.METADATA_OPERATION_ID_KEY, operationId.toString());
        assertThat(command.operationId()).isEqualTo(operationId);
        assertThat(command.subscriptionRef()).isEqualTo(subscriptionRef);
        assertThat(command.targetStripePriceRef()).isEqualTo(TO_STRIPE_PRICE_REF);
    }

    // ═════════ AC-32〜35: E6' の4検体 ═════════

    @Test
    @DisplayName("AC-32: 同期成功（pending_update=null）でも PENDING_PAYMENT を経由する（確定は invoice.paid だけ）")
    void AC32_同期成功はPENDING_PAYMENTを経由する() throws Exception {
        stubStripeApply(INVOICE_REF, "paid", false);
        UUID previewId = createPreviewId();

        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"));

        assertThat(requireSingleChange().getStatus())
                .as("API の時点で APPLIED にしない（確定の主体は webhook・E6'）")
                .isEqualTo(BillingContractChangeStatus.PENDING_PAYMENT);
        assertThat(pointerCount()).as("決着まで pointer を保持する").isEqualTo(1L);
    }

    @Test
    @DisplayName("AC-33: 3DS 要求（pending_update!=null）は REQUIRES_ACTION へ進み失効時刻を保存する")
    void AC33_3DS要求はREQUIRES_ACTION() throws Exception {
        stubStripeApply(INVOICE_REF, "open", true);
        UUID previewId = createPreviewId();

        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("REQUIRES_ACTION"));

        BillingContractChangeEntity row = requireSingleChange();
        assertThat(row.getStatus()).isEqualTo(BillingContractChangeStatus.REQUIRES_ACTION);
        assertThat(row.getPendingUpdateExpiresAt())
                .as("E2': 照合は保存した値で行うため、ここで保存していなければ後段が成立しない")
                .isNotNull();
        assertThat(row.getPendingUpdateTargetSnapshot()).isNotBlank();
    }

    @Test
    @DisplayName("AC-34: 差額0円で PaymentIntent が無くても invoice.paid を待って APPLIED にする（別経路を作らない）")
    void AC34_差額0円でも即APPLIEDにしない() throws Exception {
        stubStripeApply(null, null, false);
        UUID previewId = createPreviewId();

        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"));

        assertThat(requireSingleChange().getStatus())
                .as("0円の同期適用という別経路を作らない（E6' の一本化）")
                .isEqualTo(BillingContractChangeStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("AC-35: Stripe 応答の時点で invoice.paid が既に処理済みなら 202 で status='APPLIED' を返す")
    void AC35_webhook先行なら202でAPPLIED() throws Exception {
        // webhook が Stripe 応答より先に change を確定させた世界を作る。
        org.mockito.BDDMockito.given(planChangeGateway.applyPlanChange(org.mockito.ArgumentMatchers.any()))
                .willAnswer(invocation -> {
                    markLatestChangeApplied();
                    return new BillingPlanChangeGateway.PlanChangeApplyResult(
                            INVOICE_REF, "paid", false, null, null,
                            Instant.now().truncatedTo(ChronoUnit.SECONDS));
                });
        UUID previewId = createPreviewId();

        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("APPLIED"));

        assertThat(requireSingleChange().getStatus())
                .as("API は現状を読むだけ。webhook の確定を APPLIED から巻き戻さない")
                .isEqualTo(BillingContractChangeStatus.APPLIED);
    }

    // ═════════ AC-36: paid の前に権利を発行しない ═════════

    @Test
    @DisplayName("AC-36: invoice.paid の前は新しい権利を発行しない（プラン名も旧プランのまま）")
    void AC36_paid前に権利を発行しない() throws Exception {
        long entitlementsBefore = activeEntitlementCount();
        UUID previewId = createPreviewId();

        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isAccepted());

        assertThat(reloadContract().getPlanKey())
                .as("支払い前に上位プランへ切り替えない").isEqualTo(FROM_PLAN_KEY);
        assertThat(reloadContract().getPriceBandVersionId())
                .as("band も切り替えない").isEqualTo(fromBandId);
        assertThat(activeEntitlementCount())
                .as("新しい権利行を増やさない").isEqualTo(entitlementsBefore);
    }

    // ═════════ AC-46: Stripe 失敗 ═════════

    @Test
    @DisplayName("AC-46: Stripe 呼び出しが失敗したら operation FAILED ＋ pointer 解放、HTTP 502")
    void AC46_Stripe失敗は502でpointer解放() throws Exception {
        UUID previewId = createPreviewId();
        org.mockito.BDDMockito.willThrow(new IllegalStateException("stripe down"))
                .given(planChangeGateway).applyPlanChange(org.mockito.ArgumentMatchers.any());

        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isBadGateway());

        BillingContractChangeEntity row = requireSingleChange();
        assertThat(row.getStatus()).isEqualTo(BillingContractChangeStatus.FAILED);
        assertThat(operationOf(row.getOperationId()).getStatus())
                .isEqualTo(BillingOperationStatus.FAILED);
        assertThat(pointerCount())
                .as("失敗確定では pointer を解放する（契約を永久凍結させない）").isZero();
        assertThat(reloadContract().getPlanKey()).as("旧プランのまま").isEqualTo(FROM_PLAN_KEY);
    }

    // ═════════ AC-47: 冪等 ═════════

    @Test
    @DisplayName("AC-47: 同一 Idempotency-Key の再送は同一レスポンスを返し Stripe 呼び出しは1回")
    void AC47_同一キーの再送は同一レスポンス() throws Exception {
        UUID previewId = createPreviewId();
        String key = newKey();
        long version = contractVersion();

        MvcResult first = change(userId, contractId, previewId, version, key)
                .andExpect(status().isAccepted()).andReturn();
        MvcResult second = change(userId, contractId, previewId, version, key)
                .andExpect(status().isAccepted()).andReturn();

        JsonNode firstData = body(first).path("data");
        JsonNode secondData = body(second).path("data");
        assertThat(secondData.path("changeId").asText())
                .as("再送は同じ changeId を返す").isEqualTo(firstData.path("changeId").asText());
        assertThat(secondData.path("status").asText()).isEqualTo(firstData.path("status").asText());
        assertThat(planChangeCalls("applyPlanChange")).as("Stripe へは1回だけ").isEqualTo(1L);
        assertThat(changesOf()).as("変更行も1件だけ").hasSize(1);
    }

    // ═════════ ヘルパ ═════════

    /** AC-35: webhook が先に確定させた状態を作る（Stripe 応答の最中に割り込む）。 */
    private void markLatestChangeApplied() {
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createQuery(
                            "SELECT c FROM BillingContractChangeEntity c "
                                    + "WHERE c.contractId = :cid AND c.deletedAt IS NULL",
                            BillingContractChangeEntity.class)
                    .setParameter("cid", contractId)
                    .getResultList()
                    .forEach(row -> {
                        row.setStatus(BillingContractChangeStatus.APPLIED);
                        row.setStripeInvoiceRef(INVOICE_REF);
                    });
            entityManager.flush();
        });
    }
}
