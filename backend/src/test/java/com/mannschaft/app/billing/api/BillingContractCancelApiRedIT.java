package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.billing.ActiveBillingContractOperationPointerEntity;
import com.mannschaft.app.billing.ActiveBillingContractOperationPointerRepository;
import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractOperationEntity;
import com.mannschaft.app.billing.BillingContractOperationRepository;
import com.mannschaft.app.billing.BillingContractService;
import com.mannschaft.app.billing.BillingOperationActorKind;
import com.mannschaft.app.billing.BillingOperationKind;
import com.mannschaft.app.billing.BillingOperationStatus;
import com.mannschaft.app.billing.BillingOperationStep;
import com.mannschaft.app.billing.ContractStatus;
import com.mannschaft.app.billing.EntitlementEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Billing Center PR6a — B群 解約の受け入れテスト（試練・red）。
 *
 * <p>対象 AC: AC-22 / AC-23 / AC-24 / AC-25 / AC-26 / AC-27 / AC-33 / AC-35 / AC-36 / AC-38。
 * 境界（AC-34/37/37b/37c）は {@link BillingContractCancelPeriodEndBoundaryRedIT}、
 * 冪等（AC-28〜32）は {@link BillingContractCancelIdempotencyRedIT} が担う。</p>
 *
 * <p><b>空虚な緑への備え</b>: 「実装が無いから何も起きない＝条件を満たす」形の緑を避けるため、
 * 各否定検証（DB 無変更・Stripe 未呼出）には必ず陽性対照（正常系で DB が変わり Stripe が呼ばれる）
 * を対で置いている。陽性対照が赤であるうちは、否定側の緑を通過の根拠にしてはならない。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6a 解約 API（B群 AC-22〜38・試練 red）")
class BillingContractCancelApiRedIT extends AbstractBillingCancelResumeApiIT {

    private static final String SUB_REF = "sub_pr6a_cancel";

    @Autowired private BillingContractOperationRepository operationRepository;
    @Autowired private ActiveBillingContractOperationPointerRepository pointerRepository;
    @Autowired private BillingContractService billingContractService;

    private Long userId;
    private LocalDateTime periodEnd;

    @BeforeEach
    void setUp() {
        userId = insertUser("cancel");
        periodEnd = LocalDateTime.now(clock).plusDays(20).withNano(0);
        // AC-34: 期末の権威は Stripe の実物。解約の mutation 呼び出し（AC-39 で operationId 付きへ拡張される）
        // の戻り値ではなく、この snapshot から endAt を決めることを要求する。
        stubStripeSubscription(SUB_REF, false, periodEnd);
    }

    @AfterEach
    void tearDown() {
        cleanupScope(userId);
    }

    // ═════════ AC-22 / AC-23: 正常系（陽性対照そのもの） ═════════

    @Test
    @DisplayName("AC-22: 有償ACTIVE契約のcancelは200でscheduledAt/endAt/status=SCHEDULEDを返しStripeへcancel_at_period_endを送る")
    void AC22_有償解約は200でSCHEDULEDとendAtを返す() throws Exception {
        UUID contractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd, null);

        MvcResult result = cancel(userId, contractId, 0L, newKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.data.scheduledAt").isNotEmpty())
                .andExpect(jsonPath("$.data.endAt").isNotEmpty())
                .andReturn();

        JsonNode data = body(result).path("data");
        assertThat(data.path("endAt").asText())
                .as("endAt は current_period_end と同値（AC-22）")
                .startsWith(periodEnd.toLocalDate().toString());
        assertThat(data.path("canResume").asBoolean())
                .as("解約直後は撤回できる（AC-46 の窓が開いている）").isTrue();

        // Stripe に cancel_at_period_end を実際に送ったこと（送らずに 200 を返す空虚な緑を排除する）。
        assertThat(stripeCallsFor("cancelAtPeriodEnd", SUB_REF))
                .as("Stripe へ cancel_at_period_end を1回送る").isEqualTo(1L);
    }

    @Test
    @DisplayName("AC-23: cancel後もcontractはACTIVEのままcancelled_atがセットされる（EXPIREDにしない）")
    void AC23_契約はACTIVEのままcancelledAtが入る() throws Exception {
        UUID contractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd, null);

        cancel(userId, contractId, 0L, newKey()).andExpect(status().isOk());

        BillingContractEntity after = reloadContract(contractId);
        assertThat(after.getStatus()).as("期末までは利用できるので ACTIVE のまま").isEqualTo(ContractStatus.ACTIVE);
        assertThat(after.getCancelledAt()).as("解約予約の目印は cancelled_at").isNotNull();
    }

    // ═════════ AC-24: 権利の期限（半開区間） ═════════

    @Test
    @DisplayName("AC-24: 由来entitlementsのvalid_untilがcurrent_period_endになり期末ちょうどは無効（半開区間）")
    void AC24_valid_untilは期末で期末ちょうどは無効() throws Exception {
        UUID contractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd, null);
        insertEntitlement(userId, contractId, FEATURE_KEY);

        cancel(userId, contractId, 0L, newKey()).andExpect(status().isOk());

        List<EntitlementEntity> rows = reloadEntitlements(contractId);
        assertThat(rows).hasSize(1);
        EntitlementEntity e = rows.get(0);
        assertThat(e.getValidUntil()).as("valid_until は current_period_end").isEqualTo(periodEnd);
        assertThat(e.isActiveAt(periodEnd))
                .as("半開区間 [from, until)。期末ちょうどは無効（AC-24）").isFalse();
        assertThat(e.isActiveAt(periodEnd.minusSeconds(1)))
                .as("期末の1秒前はまだ有効（境界の向きを固定する陽性対照）").isTrue();
    }

    // ═════════ AC-25: 無償契約は即時失効（既存挙動を壊さない） ═════════

    @Test
    @DisplayName("AC-25: 無償契約のcancelは即時失効（CANCELLED＋権利revoke・Stripeを呼ばない）")
    void AC25_無償契約は即時失効() throws Exception {
        UUID contractId = insertContract(userId, ContractStatus.ACTIVE, null, null, null, null);
        insertEntitlement(userId, contractId, FEATURE_KEY);

        cancel(userId, contractId, 0L, newKey()).andExpect(status().isOk());

        BillingContractEntity after = reloadContract(contractId);
        assertThat(after.getStatus()).as("無償は期末を待たず即 CANCELLED").isEqualTo(ContractStatus.CANCELLED);
        assertThat(reloadEntitlements(contractId)).as("由来権利は revoke 済み").isEmpty();
        assertThat(stripeCalls("cancelAtPeriodEnd")).as("無償契約で Stripe を呼んではならない").isZero();
    }

    // ═════════ AC-26 / AC-27: 再解約・version ═════════

    @Test
    @DisplayName("AC-26: 既にcancelled_atのある契約への再cancelは409 ENTITLEMENT_011でStripeを呼ばない")
    void AC26_再解約は409() throws Exception {
        UUID contractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd,
                LocalDateTime.now(clock).minusDays(1));

        cancel(userId, contractId, 0L, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_011"));

        assertThat(stripeCalls("cancelAtPeriodEnd")).as("再解約で Stripe へ再送しない").isZero();
    }

    @Test
    @DisplayName("AC-27: version不一致のcancelは409でDBを一切変更しない")
    void AC27_version不一致は409() throws Exception {
        UUID contractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd, null);

        cancel(userId, contractId, 999L, newKey()).andExpect(status().isConflict());

        assertThat(reloadContract(contractId).getCancelledAt())
                .as("CAS に落ちた要求は DB を変えない").isNull();
        assertThat(stripeCalls("cancelAtPeriodEnd")).as("CAS に落ちたら Stripe を呼ばない").isZero();
    }

    // ═════════ AC-36: PAST_DUE（D4） ═════════

    @Test
    @DisplayName("AC-36: PAST_DUE契約のcancelは許可される（期末解約として扱う）")
    void AC36_PAST_DUEでも解約できる() throws Exception {
        UUID contractId = insertContract(userId, ContractStatus.PAST_DUE, PRICE_JPY, SUB_REF, periodEnd, null);

        cancel(userId, contractId, 0L, newKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"));

        assertThat(reloadContract(contractId).getCancelledAt()).isNotNull();
        assertThat(stripeCallsFor("cancelAtPeriodEnd", SUB_REF)).isEqualTo(1L);
    }

    // ═════════ AC-35: Stripe 失敗（途中失敗） ═════════

    /**
     * <p><b>第6隊への申し送り</b>: AC-39 で mutation が {@code cancelAtPeriodEnd(ref, operationId)} へ移ったら、
     * 本テストの {@code willThrow} も2引数版へ移すこと。1引数版のまま放置すると例外が飛ばなくなる。
     * ただし本テストは 502 を期待しているため、飛ばなくなった瞬間に 200 で落ちる（偽緑にはならない）。</p>
     */
    @Test
    @DisplayName("AC-35: Stripe失敗ならHTTP502でcontractはACTIVEのままcancelled_atが入らない")
    void AC35_Stripe失敗は502で旧状態維持() throws Exception {
        UUID contractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd, null);
        willThrow(new IllegalStateException("stripe down"))
                .given(billingPaymentGateway).cancelAtPeriodEnd(anyString(), any());

        cancel(userId, contractId, 0L, newKey()).andExpect(status().isBadGateway());

        BillingContractEntity after = reloadContract(contractId);
        assertThat(after.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        assertThat(after.getCancelledAt()).as("Stripe が失敗したなら解約予約は残さない").isNull();
    }

    @Test
    @DisplayName("AC-35: Stripe失敗でoperationはFAILEDへ倒れpointerが解放される")
    void AC35_Stripe失敗でoperationはFAILEDでpointer解放() throws Exception {
        UUID contractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd, null);
        willThrow(new IllegalStateException("stripe down"))
                .given(billingPaymentGateway).cancelAtPeriodEnd(anyString(), any());

        cancel(userId, contractId, 0L, newKey()).andExpect(status().isBadGateway());

        List<BillingContractOperationEntity> failed = operationRepository
                .findByContractIdAndStatusAndDeletedAtIsNull(contractId, BillingOperationStatus.FAILED);
        assertThat(failed).as("失敗は FAILED として記録される（記録が無いのは空虚な緑）").hasSize(1);
        assertThat(pointerRepository.findById(contractId))
                .as("FAILED は terminal。pointer は同一トランザクションで解放される").isEmpty();
    }

    // ═════════ AC-38: pointer 競合は ENTITLEMENT_021 ═════════

    @Test
    @DisplayName("AC-38: pointer在りの契約へのcancelは409 ENTITLEMENT_021（新コードを採番しない）")
    void AC38_pointer競合はENTITLEMENT_021() throws Exception {
        UUID contractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd, null);
        UUID operationId = seedInFlightOperation(contractId);

        cancel(userId, contractId, 0L, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_021"));

        assertThat(pointerRepository.findById(contractId))
                .as("競合で弾いた側が他人の lease を消してはならない").isPresent()
                .get().extracting(ActiveBillingContractOperationPointerEntity::getOperationId)
                .isEqualTo(operationId);
        assertThat(stripeCalls("cancelAtPeriodEnd")).as("競合で弾いたなら Stripe を呼ばない").isZero();
    }

    // ═════════ AC-33: customer.subscription.deleted ═════════

    @Test
    @DisplayName("AC-33: subscription.deleted受信でEXPIRED＋operation pointer削除＋残entitlements revoke")
    void AC33_subscriptionDeletedでEXPIREDとpointer削除() {
        UUID contractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd,
                LocalDateTime.now(clock).minusDays(1));
        insertEntitlement(userId, contractId, FEATURE_KEY);
        seedInFlightOperation(contractId);

        billingContractService.expireSubscriptionContract(SUB_REF, LocalDateTime.now(clock));

        assertThat(reloadContract(contractId).getStatus()).isEqualTo(ContractStatus.EXPIRED);
        assertThat(reloadEntitlements(contractId)).as("残り権利は revoke される").isEmpty();
        assertThat(pointerRepository.findById(contractId))
                .as("EXPIRED 確定で operation pointer も解放される（AC-33）").isEmpty();
    }

    // ============================================================
    // ヘルパ
    // ============================================================

    /** 進行中（非終端）の operation ＋ pointer を直に置く（第5隊の lease 取得の成果物と同じ形）。 */
    private UUID seedInFlightOperation(UUID contractId) {
        return transactionTemplate.execute(tx -> {
            BillingContractOperationEntity op = BillingContractOperationEntity.builder()
                    .contractId(contractId)
                    .billingCustomerId(UUID.randomUUID())
                    .kind(BillingOperationKind.PLAN_CHANGE)
                    .status(BillingOperationStatus.CALLING_STRIPE)
                    .step(BillingOperationStep.STRIPE_APPLY_PLAN_CHANGE)
                    .idempotencyKey(UUID.randomUUID().toString())
                    .requestHash("0".repeat(64))
                    .version(0L)
                    .actorKind(BillingOperationActorKind.USER)
                    .createdBy(userId)
                    .build();
            entityManager.persist(op);
            entityManager.persist(ActiveBillingContractOperationPointerEntity.builder()
                    .contractId(contractId).operationId(op.getId()).build());
            entityManager.flush();
            return op.getId();
        });
    }
}
