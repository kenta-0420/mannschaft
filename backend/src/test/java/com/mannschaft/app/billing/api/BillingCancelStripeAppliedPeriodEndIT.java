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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Billing Center PR6a — Codex 検分 P2-1: 期末は<b>変更を適用した後</b>に Stripe が返した値で書く。
 *
 * <h2>何が壊れていたか</h2>
 * <p>期末は2度観測できる。事前取得（{@code retrieveSubscription}・409 判定に使う）と、
 * 変更系（{@code cancelAtPeriodEnd} / {@code revertCancelAtPeriodEnd}）の戻り値である。
 * 実装は後者を捨てて前者を DB と {@code valid_until} へ書いていた。</p>
 *
 * <p>この二つの観測の間に請求期間の更新が挟まると、Stripe 側は<b>次期間</b>の末日で解約予約された
 * 一方、DB と権利には<b>前期間</b>の末日が残る。利用者から見れば「○月○日まで使えます」と
 * 言われた日付より前に使えなくなる（AC-24 が守ろうとしたものが境界で破れる）。
 * AC-34 が定めたのは「期末は Stripe が権威」であって「事前取得が権威」ではない。</p>
 *
 * <p><b>検体</b>: 事前取得と変更系で<b>異なる</b>期末を返させ、書かれた値が後者であることを
 * 応答・契約行・entitlements の3箇所すべてで測る（1箇所だけ直す実装を通さない）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6a P2-1: 変更後にStripeが返した期末を書く（実MySQL）")
class BillingCancelStripeAppliedPeriodEndIT extends AbstractBillingCancelResumeApiIT {

    private static final String SUB_REF = "sub_pr6a_applied_end";

    private Long userId;
    /** 事前取得が返す期末（＝請求期間の更新前）。 */
    private LocalDateTime beforeRenewal;
    /** 変更系が返す期末（＝請求期間の更新後。こちらが権威）。 */
    private LocalDateTime afterRenewal;

    @BeforeEach
    void setUp() {
        userId = insertUser("appliedend");
        beforeRenewal = LocalDateTime.now(clock).plusDays(2).withNano(0);
        afterRenewal = LocalDateTime.now(clock).plusDays(32).withNano(0);
    }

    @AfterEach
    void tearDown() {
        cleanupScope(userId);
    }

    @Test
    @DisplayName("P2-1: 解約 — 事前取得より後の期末を cancelAtPeriodEnd が返したら、その値が応答・契約・valid_until に書かれる")
    void cancelUsesPeriodEndReturnedByStripeMutation() throws Exception {
        UUID contractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF,
                beforeRenewal, null);
        insertEntitlement(userId, contractId, FEATURE_KEY);
        stubStripeSubscription(SUB_REF, false, beforeRenewal);
        given(billingPaymentGateway.cancelAtPeriodEnd(anyString(), any()))
                .willReturn(afterRenewal.atZone(clock.getZone()).toInstant());

        MvcResult result = cancel(userId, contractId, 0L, newKey())
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = body(result).path("data");
        assertThat(data.path("endAt").asText())
                .as("応答の endAt は【変更後】の期末（利用者へ約束する日付）")
                .startsWith(afterRenewal.toLocalDate().toString());
        assertThat(data.path("currentPeriodEnd").asText())
                .startsWith(afterRenewal.toLocalDate().toString());

        BillingContractEntity after = reloadContract(contractId);
        assertThat(after.getCurrentPeriodEnd())
                .as("契約行も【変更後】の期末（Stripe と DB の食い違いを残さない）")
                .isEqualTo(afterRenewal);

        List<EntitlementEntity> rows = reloadEntitlements(contractId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getValidUntil())
                .as("valid_until も【変更後】の期末。ここが古いと約束より早く使えなくなる")
                .isEqualTo(afterRenewal);
        assertThat(rows.get(0).isActiveAt(beforeRenewal.plusSeconds(1)))
                .as("事前取得の期末を過ぎても、変更後の期末までは有効であること").isTrue();
    }

    @Test
    @DisplayName("P2-1: 撤回 — revertCancelAtPeriodEnd が返した期末が契約行に書かれる")
    void resumeUsesPeriodEndReturnedByStripeMutation() throws Exception {
        UUID contractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF,
                beforeRenewal, LocalDateTime.now(clock).minusDays(1));
        stubStripeSubscription(SUB_REF, true, beforeRenewal);
        given(billingPaymentGateway.revertCancelAtPeriodEnd(anyString(), any()))
                .willReturn(afterRenewal.atZone(clock.getZone()).toInstant());

        MvcResult result = resume(userId, contractId, 0L, newKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn();

        assertThat(body(result).path("data").path("endAt").asText())
                .startsWith(afterRenewal.toLocalDate().toString());
        assertThat(reloadContract(contractId).getCurrentPeriodEnd()).isEqualTo(afterRenewal);
    }

    @Test
    @DisplayName("P2-1: 陽性対照 — 変更系が期末を返さない（null）なら事前取得の値へ落ちる（AC-37c の非null保証を壊さない）")
    void fallsBackToPreFetchedPeriodEndWhenMutationReturnsNull() throws Exception {
        LocalDateTime periodEnd = LocalDateTime.now(clock).plusDays(15).withNano(0);
        UUID contractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF,
                periodEnd, null);
        stubStripeSubscription(SUB_REF, false, periodEnd);
        given(billingPaymentGateway.cancelAtPeriodEnd(anyString(), any())).willReturn(null);

        MvcResult result = cancel(userId, contractId, 0L, newKey())
                .andExpect(status().isOk())
                .andReturn();

        assertThat(body(result).path("data").path("endAt").asText())
                .as("変更系が期末を返さない実装でも endAt は非 null（AC-37c）")
                .startsWith(periodEnd.toLocalDate().toString());
        assertThat(reloadContract(contractId).getCurrentPeriodEnd()).isEqualTo(periodEnd);
    }

    @Test
    @DisplayName("P2-1: 409 判定の順序は不変 — 事前取得の期末が過去なら、変更系が未来を返す用意があっても Stripe を呼ばず 409")
    void gateStillUsesPreFetchedPeriodEndAndNeverCallsMutation() throws Exception {
        LocalDateTime past = LocalDateTime.now(clock).minusDays(3).withNano(0);
        UUID contractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, past, null);
        insertEntitlement(userId, contractId, FEATURE_KEY);
        stubStripeSubscription(SUB_REF, false, past);
        // 「変更系を呼べば未来の期末が返る」状況を用意したうえで、それでも呼ばれないことを測る。
        given(billingPaymentGateway.cancelAtPeriodEnd(anyString(), any()))
                .willReturn(afterRenewal.atZone(clock.getZone()).toInstant());

        cancel(userId, contractId, 0L, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_011"));

        assertThat(stripeCalls("cancelAtPeriodEnd"))
                .as("409 を返す前に Stripe の変更系を呼んではならない（この順序は P2-1 でも不変）")
                .isZero();
        assertThat(reloadContract(contractId).getCancelledAt()).as("DB は変えない").isNull();
        assertThat(reloadEntitlements(contractId).get(0).getValidUntil())
                .as("権利も触らない").isNull();
    }
}
