package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.ContractStatus;
import com.mannschaft.app.billing.EntitlementEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Billing Center PR6a — C群 解約撤回の受け入れテスト（試練・red）。
 *
 * <p>対象 AC: AC-40 / AC-41 / AC-42 / AC-43 / AC-44 / AC-45 / AC-46。
 * 引継との排他（AC-48）と夜次照合の衝突（AC-49）は
 * {@link BillingCancelResumeHandoverExclusionRedIT} が担う。</p>
 *
 * <h2>第6隊への発注書（撤回の Stripe 呼び出し・AC-47）</h2>
 * <p>billing ポート {@code BillingPaymentGateway} に
 * {@code void revertCancelAtPeriodEnd(String subscriptionRef, UUID operationId)} を<b>新設</b>し、
 * 実体は既存 {@code payment/stripe/StripePaymentProviderImpl#revertSubscriptionCancelAtPeriodEnd}
 * （:1410-1429）を再利用する。引継専用キー {@code billing-handover-revert-cancel-*} は使わず、
 * {@code billing-operation-{operationId}} を冪等キーにする（AC-5 と同じ名前空間）。
 * 既存の {@code revertCancelAtPeriodEndForHandover} とは<b>別メソッド</b>であり、
 * どちらが呼ばれたかを本 IT はメソッド名で区別して測る。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6a 解約撤回 API（C群 AC-40〜46・試練 red）")
class BillingContractResumeApiRedIT extends AbstractBillingCancelResumeApiIT {

    private static final String SUB_REF = "sub_pr6a_resume";

    private Long userId;
    private LocalDateTime periodEnd;

    @BeforeEach
    void setUp() {
        userId = insertUser("resume");
        periodEnd = LocalDateTime.now(clock).plusDays(12).withNano(0);
        stubStripeSubscription(SUB_REF, true, periodEnd);
    }

    @AfterEach
    void tearDown() {
        cleanupScope(userId);
    }

    /** 「期末解約を予約済み」の契約（撤回の出発点）。 */
    private UUID scheduledContract(LocalDateTime end, ContractStatus status) {
        UUID id = insertContract(userId, status, PRICE_JPY, SUB_REF, end,
                LocalDateTime.now(clock).minusDays(1));
        return id;
    }

    // ═════════ AC-40: 正常系 ═════════

    @Test
    @DisplayName("AC-40: cancelled_atのある契約へのDELETE …/cancelは200でendAt/status=ACTIVEを返しStripeへcancel_at_period_end=falseを送る")
    void AC40_撤回は200でACTIVEを返す() throws Exception {
        UUID contractId = scheduledContract(periodEnd, ContractStatus.ACTIVE);

        MvcResult result = resume(userId, contractId, 0L, newKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.endAt").isNotEmpty())
                .andReturn();

        JsonNode data = body(result).path("data");
        assertThat(data.path("scheduledAt").isNull()).as("撤回後は解約予約時刻を持たない").isTrue();
        assertThat(data.path("canResume").asBoolean()).as("もう撤回するものは無い").isFalse();
        assertThat(data.path("canCancel").asBoolean()).as("改めて解約はできる").isTrue();

        assertThat(reloadContract(contractId).getCancelledAt())
                .as("DB の解約予約も消える").isNull();
        assertThat(stripeCallsFor("revertCancelAtPeriodEnd", SUB_REF))
                .as("billing ポートの新メソッド（AC-47）を1回呼ぶ").isEqualTo(1L);
        assertThat(stripeCalls("revertCancelAtPeriodEndForHandover"))
                .as("引継専用の差し戻し経路を流用してはならない（AC-47）").isZero();
    }

    // ═════════ AC-41: D5 — valid_until は NULL へ戻る ═════════

    @Test
    @DisplayName("AC-41: 撤回でentitlementsのvalid_untilがNULL（無期限）へ戻る")
    void AC41_valid_untilはNULLへ戻る() throws Exception {
        UUID contractId = scheduledContract(periodEnd, ContractStatus.ACTIVE);
        UUID entitlementId = insertEntitlement(userId, contractId, FEATURE_KEY);
        // 解約予約済みの状態を再現する（valid_until = 期末）。
        transactionTemplate.executeWithoutResult(tx -> {
            EntitlementEntity e = entityManager.find(EntitlementEntity.class, entitlementId);
            e.setValidUntil(periodEnd);
            entityManager.flush();
        });

        resume(userId, contractId, 0L, newKey()).andExpect(status().isOk());

        List<EntitlementEntity> rows = reloadEntitlements(contractId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getValidUntil())
                .as("D5: 発行時は常に NULL であり、旧値は保存されないので NULL へ戻す").isNull();
        assertThat(rows.get(0).isActiveAt(periodEnd.plusYears(1)))
                .as("無期限に戻ったことの成果物側の確認").isTrue();
    }

    // ═════════ AC-42 / AC-43 / AC-44: 拒否 ═════════

    @Test
    @DisplayName("AC-42: cancelled_atが無い契約への撤回は409でStripeを呼ばない")
    void AC42_解約予約が無ければ409() throws Exception {
        UUID contractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd, null);

        resume(userId, contractId, 0L, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_011"));

        assertThat(stripeCalls("revertCancelAtPeriodEnd")).isZero();
    }

    @Test
    @DisplayName("AC-43: 期末を過ぎてEXPIREDになった契約への撤回は409（復活させない）")
    void AC43_EXPIRED契約は復活しない() throws Exception {
        LocalDateTime past = LocalDateTime.now(clock).minusDays(2).withNano(0);
        UUID contractId = scheduledContract(past, ContractStatus.EXPIRED);
        stubStripeSubscription(SUB_REF, true, past);

        resume(userId, contractId, 0L, newKey()).andExpect(status().isConflict());

        BillingContractEntity after = reloadContract(contractId);
        assertThat(after.getStatus()).as("EXPIRED のまま（ACTIVE へ戻さない）").isEqualTo(ContractStatus.EXPIRED);
        assertThat(stripeCalls("revertCancelAtPeriodEnd")).isZero();
    }

    @Test
    @DisplayName("AC-44: 撤回のversion不一致は409でDBを一切変更しない")
    void AC44_version不一致は409() throws Exception {
        UUID contractId = scheduledContract(periodEnd, ContractStatus.ACTIVE);

        resume(userId, contractId, 999L, newKey()).andExpect(status().isConflict());

        assertThat(reloadContract(contractId).getCancelledAt())
                .as("CAS に落ちた撤回は解約予約を消さない").isNotNull();
        assertThat(stripeCalls("revertCancelAtPeriodEnd")).isZero();
    }

    // ═════════ AC-45: 冪等 ═════════

    @Test
    @DisplayName("AC-45: 撤回のIdempotency-Key再送で同一結果（Stripe呼び出しは1回）")
    void AC45_撤回の同一キー再送は1回だけ() throws Exception {
        UUID contractId = scheduledContract(periodEnd, ContractStatus.ACTIVE);
        String key = newKey();

        MvcResult first = resume(userId, contractId, 0L, key).andExpect(status().isOk()).andReturn();
        MvcResult second = resume(userId, contractId, 0L, key).andExpect(status().isOk()).andReturn();

        assertThat(body(second).path("data")).isEqualTo(body(first).path("data"));
        assertThat(stripeCallsFor("revertCancelAtPeriodEnd", SUB_REF)).isEqualTo(1L);
    }

    // ═════════ AC-46: 撤回できるのは当月末まで ═════════

    @Test
    @DisplayName("AC-46: 期末を跨いだ契約はcanResume=falseであり撤回は409（webhook未達でACTIVEのままでも同じ）")
    void AC46_期末を跨いだら撤回できない() throws Exception {
        LocalDateTime past = LocalDateTime.now(clock).minusHours(1).withNano(0);
        // customer.subscription.deleted がまだ届かず status は ACTIVE のまま、という現実の検体。
        UUID contractId = scheduledContract(past, ContractStatus.ACTIVE);
        stubStripeSubscription(SUB_REF, true, past);

        resume(userId, contractId, 0L, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_011"));

        assertThat(reloadContract(contractId).getCancelledAt())
                .as("撤回窓の外なので解約予約は残る").isNotNull();
        assertThat(stripeCalls("revertCancelAtPeriodEnd")).isZero();
    }

    @Test
    @DisplayName("AC-46: 陽性対照 — 期末前ならcanResume=trueで撤回できる（窓の向きを一意にする）")
    void AC46_陽性対照_期末前なら撤回できる() throws Exception {
        UUID contractId = scheduledContract(periodEnd, ContractStatus.ACTIVE);

        resume(userId, contractId, 0L, newKey()).andExpect(status().isOk());

        assertThat(reloadContract(contractId).getCancelledAt()).isNull();
    }
}
