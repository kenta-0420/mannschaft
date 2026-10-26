package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPayerHandoverRequestEntity;
import com.mannschaft.app.billing.BillingPayerHandoverRequestRepository;
import com.mannschaft.app.billing.BillingPayerHandoverService;
import com.mannschaft.app.billing.ContractStatus;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.PayerHandoverStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Billing Center PR6a — 引継との排他（AC-48）と夜次照合との衝突（AC-49）の受け入れテスト（試練・red）。
 *
 * <h2>AC-48 — なぜ「状態だけを見る」実装では素通りするのか</h2>
 * <p>{@code PENDING_HANDOVER} は引継の<b>新</b>契約の状態である
 * （{@code BillingPayerHandoverTxService}:448, 1069-1072）。利用者が解約・撤回で触るのは<b>旧</b>契約であり、
 * その status は最後まで {@code ACTIVE} のままである。したがって
 * 「contract.status が PENDING_HANDOVER なら弾く」という実装は<b>一度も発火しない</b>。
 * 判定は {@code billing_payer_handover_requests.old_contract_id = {contractId}} かつ status が
 * 非終端（{@code BillingPayerHandoverTxService#TERMINAL_STATUSES} に含まれない）であることで行う。
 * 本 IT の検体は旧契約側なので、素通りする実装では 200 が返り必ず落ちる。</p>
 *
 * <h2>AC-49 — 撤回が翌朝消える経路</h2>
 * <p>引継の夜次照合 {@code BillingPayerHandoverService#reconcileOldCancelSchedule} は、Stripe 実物の
 * {@code cancel_at_period_end} が false なら<b>無条件に true を再設定する</b>（:779-790）。
 * 撤回が通ってしまうと、その晩の照合で解約予約が復活し、利用者の撤回は翌朝消える。
 * よって撤回は 409 で弾かれ、かつ照合を<b>実際に1周回して</b>再設定が起きないことまで測る。
 * 「409 だから大丈夫」と推論で済ませない。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6a 引継との排他と夜次照合の衝突（AC-48/49・試練 red）")
class BillingCancelResumeHandoverExclusionRedIT extends AbstractBillingCancelResumeApiIT {

    private static final String SUB_REF = "sub_pr6a_handover";

    @Autowired private BillingPayerHandoverRequestRepository handoverRequestRepository;
    @Autowired private BillingPayerHandoverService handoverService;

    private Long userId;
    private LocalDateTime periodEnd;
    private UUID contractId;

    @BeforeEach
    void setUp() {
        userId = insertUser("handover");
        periodEnd = LocalDateTime.now(clock).plusDays(15).withNano(0);
        // 旧契約は最後まで ACTIVE。PENDING_HANDOVER になるのは新契約側である（AC-48 の罠）。
        contractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd, null);
        stubStripeSubscription(SUB_REF, true, periodEnd);
    }

    @AfterEach
    void tearDown() {
        cleanupScope(userId);
    }

    // ═════════ AC-48: 引継進行中は cancel / 撤回とも 409 ═════════

    @Test
    @DisplayName("AC-48: old_contract_idが一致する非終端の引継要求があるとcancelは409 ENTITLEMENT_021")
    void AC48_引継進行中のcancelは409() throws Exception {
        seedHandover(PayerHandoverStatus.SWITCHING);

        cancel(userId, contractId, 0L, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_021"));

        assertThat(reloadContract(contractId).getStatus())
                .as("検体の旧契約は ACTIVE のまま。status だけを見る実装はここで素通りする")
                .isEqualTo(ContractStatus.ACTIVE);
        assertThat(reloadContract(contractId).getCancelledAt()).isNull();
        assertThat(stripeCalls("cancelAtPeriodEnd")).isZero();
    }

    @Test
    @DisplayName("AC-48: old_contract_idが一致する非終端の引継要求があると撤回も409 ENTITLEMENT_021")
    void AC48_引継進行中の撤回は409() throws Exception {
        markScheduledCancel();
        seedHandover(PayerHandoverStatus.SWITCHING);

        resume(userId, contractId, 0L, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_021"));

        assertThat(reloadContract(contractId).getCancelledAt())
                .as("撤回は成立しない").isNotNull();
        assertThat(stripeCalls("revertCancelAtPeriodEnd")).isZero();
    }

    @Test
    @DisplayName("AC-48: 陽性対照 — 引継要求が終端（COMPLETED）なら解約できる（過剰に弾かない）")
    void AC48_陽性対照_終端の引継要求は妨げない() throws Exception {
        seedHandover(PayerHandoverStatus.COMPLETED);
        stubStripeSubscription(SUB_REF, false, periodEnd);

        cancel(userId, contractId, 0L, newKey()).andExpect(status().isOk());

        assertThat(reloadContract(contractId).getCancelledAt()).isNotNull();
    }

    @Test
    @DisplayName("AC-48: 陽性対照 — 引継要求が別契約を指しているだけなら解約できる（old_contract_idで絞る）")
    void AC48_陽性対照_別契約の引継要求は妨げない() throws Exception {
        UUID otherContractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY,
                SUB_REF + "_other", periodEnd, null);
        seedHandoverFor(otherContractId, PayerHandoverStatus.SWITCHING);
        stubStripeSubscription(SUB_REF, false, periodEnd);

        cancel(userId, contractId, 0L, newKey()).andExpect(status().isOk());
    }

    // ═════════ AC-49: 夜次照合を1周回しても撤回は復活させられない ═════════

    @Test
    @DisplayName("AC-49: 撤回が409で弾かれた後に夜次照合を1周回してもcancel_at_period_end=trueの再設定が起きない")
    void AC49_撤回拒否後の夜次照合は再設定しない() throws Exception {
        markScheduledCancel();
        UUID handoverId = seedHandover(PayerHandoverStatus.SWITCHING);

        resume(userId, contractId, 0L, newKey()).andExpect(status().isConflict());

        // 夜次照合を実際に1周回す（推論ではなく実測）。Stripe 実物は true のままなので再設定は起きない。
        handoverService.reconcileOldCancelSchedule(handoverId);

        assertThat(stripeCalls("scheduleCancelAtPeriodEndForHandover"))
                .as("撤回が弾かれていれば Stripe 実物は true のままで、照合は再設定しない").isZero();
        assertThat(reloadContract(contractId).getCancelledAt())
                .as("利用者から見て解約予約は一貫して残り続ける").isNotNull();
    }

    @Test
    @DisplayName("AC-49: 陽性対照 — 撤回が通ってStripeがfalseになっていれば夜次照合は再設定する（衝突経路が実在すること）")
    void AC49_陽性対照_撤回が通ると夜次照合が解約予約を復活させる() {
        markScheduledCancel();
        UUID handoverId = seedHandover(PayerHandoverStatus.SWITCHING);
        // 「もし撤回が通っていたら」の状態＝Stripe 実物の cancel_at_period_end が false。
        stubStripeSubscription(SUB_REF, false, periodEnd);

        handoverService.reconcileOldCancelSchedule(handoverId);

        assertThat(stripeCalls("scheduleCancelAtPeriodEndForHandover"))
                .as("撤回を通していたら翌朝この再設定で消える。AC-48/49 の排他はこの経路を塞ぐためにある")
                .isEqualTo(1L);
    }

    // ============================================================
    // ヘルパ
    // ============================================================

    /** 契約を「期末解約を予約済み」にする（撤回の出発点）。 */
    private void markScheduledCancel() {
        transactionTemplate.executeWithoutResult(tx -> {
            var c = billingContractRepository.findByIdAndDeletedAtIsNull(contractId).orElseThrow();
            c.setCancelledAt(LocalDateTime.now(clock).minusDays(1));
            billingContractRepository.saveAndFlush(c);
        });
    }

    private UUID seedHandover(PayerHandoverStatus status) {
        return seedHandoverFor(contractId, status);
    }

    private UUID seedHandoverFor(UUID oldContractId, PayerHandoverStatus status) {
        return transactionTemplate.execute(tx -> {
            BillingPayerHandoverRequestEntity h = BillingPayerHandoverRequestEntity.builder()
                    .oldContractId(oldContractId)
                    .scopeKind(EntitlementScopeKind.USER)
                    .scopeId(userId)
                    .oldPayerUserId(userId)
                    .status(status)
                    .requestedAt(clock.instant().minus(Duration.ofDays(1)))
                    .expiresAt(clock.instant().plus(Duration.ofDays(6)))
                    .acceptedAt(clock.instant().minus(Duration.ofHours(12)))
                    .build();
            entityManager.persist(h);
            entityManager.flush();
            return h.getId();
        });
    }
}
