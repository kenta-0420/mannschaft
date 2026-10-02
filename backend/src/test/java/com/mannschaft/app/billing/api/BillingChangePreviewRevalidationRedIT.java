package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingChangePreviewEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Billing Center PR6b-1 — A群 preview の再照合（AC-13〜16・試練 red）。
 *
 * <p>正本 05:291 は「価格、人数、税、契約version、期間のいずれかが異なれば新規 preview を要求する」と
 * 定めている。<b>4本に分ける理由</b>は、束ねると1つの検査（多くは契約 version）だけ実装されて
 * 残り3つが素通りしても緑になるからである。とくに<b>税率だけの改定は契約 version を動かさない</b>ため、
 * AC-12（version CAS）では構造的に捕まらない。</p>
 *
 * <p>各検体は「preview を作った後に世界を1軸だけ動かす」形にしてある。動かす軸以外は据え置きなので、
 * 409 になったならその軸を見ていたことになる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6b-1 preview の再照合（A群 AC-13〜16・試練 red）")
class BillingChangePreviewRevalidationRedIT extends AbstractBillingPlanChangeApiIT {

    @BeforeEach
    void setUp() {
        seedUpgradableContract("revalidate");
        stubStripeApply("in_pr6b1_reval", "open", true);
    }

    @AfterEach
    void tearDown() {
        cleanupScope();
    }

    @Test
    @DisplayName("AC-13〜16(陽性対照): 世界が変わっていなければ preview はそのまま消費できる")
    void AC13to16_陽性対照_変化なしなら消費できる() throws Exception {
        UUID previewId = createPreviewId();

        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isAccepted());
        assertThat(changesOf()).hasSize(1);
    }

    @Test
    @DisplayName("AC-13: 価格（band の税込額）が変わっていたら 409（新しい preview を要求する）")
    void AC13_価格が変わったら409() throws Exception {
        UUID previewId = createPreviewId();
        mutateBand(toBandId, band -> {
            band.setAmountIncludingTax(TO_AMOUNT + 1_100L);
            band.setInputAmount(TO_AMOUNT + 1_100L);
        });

        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isConflict());
        assertThat(changesOf()).as("旧価格のまま確定してはならない").isEmpty();
        assertThat(planChangeCalls("applyPlanChange")).isZero();
    }

    @Test
    @DisplayName("AC-14: 人数が変わっていたら 409")
    void AC14_人数が変わったら409() throws Exception {
        UUID previewId = createPreviewId();
        // preview が見た人数と、いま数え直した人数（契約の 1名）がずれた状態にする。
        mutatePreview(previewId, p -> p.setMemberCount(9));

        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isConflict());
        assertThat(changesOf()).isEmpty();
    }

    @Test
    @DisplayName("AC-15: 税率だけの改定でも 409（契約 version を動かさない改定を捕まえる）")
    void AC15_税率が変わったら409() throws Exception {
        UUID previewId = createPreviewId();
        long versionBefore = contractVersion();
        // 税込額は据え置きのまま、税率と税額（内訳）だけを改定する。
        mutateBand(toBandId, band -> {
            band.setTaxRateBasisPoints(800);
            band.setTaxAmount(Math.round(band.getAmountIncludingTax() * 0.08d / 1.08d));
            band.setAmountExcludingTax(band.getAmountIncludingTax()
                    - Math.round(band.getAmountIncludingTax() * 0.08d / 1.08d));
            band.setTaxMasterSnapshot("{\"name\":\"消費税\",\"rateBasisPoints\":800}");
        });

        assertThat(contractVersion())
                .as("前提: 税率改定は契約 version を動かさない（だから AC-12 では捕まらない）")
                .isEqualTo(versionBefore);

        change(userId, contractId, previewId, versionBefore, newKey())
                .andExpect(status().isConflict());
        assertThat(changesOf()).as("旧税率の見積りで確定してはならない").isEmpty();
    }

    @Test
    @DisplayName("AC-16: 期間（current_period_end）が変わっていたら 409")
    void AC16_期間が変わったら409() throws Exception {
        UUID previewId = createPreviewId();
        long versionBefore = contractVersion();
        transactionTemplate.executeWithoutResult(tx -> {
            var contract = billingContractRepository.findByIdAndDeletedAtIsNull(contractId).orElseThrow();
            contract.setCurrentPeriodEnd(LocalDateTime.now(clock).plusDays(3).withNano(0));
            entityManager.flush();
        });

        change(userId, contractId, previewId, versionBefore, newKey())
                .andExpect(status().isConflict());
        assertThat(changesOf()).as("旧期間の按分で確定してはならない").isEmpty();
    }

    private void mutatePreview(UUID previewId, java.util.function.Consumer<BillingChangePreviewEntity> mutation) {
        transactionTemplate.executeWithoutResult(tx -> {
            BillingChangePreviewEntity row = entityManager.find(BillingChangePreviewEntity.class, previewId);
            if (row == null) {
                throw new AssertionError("preview が無い: " + previewId);
            }
            mutation.accept(row);
            entityManager.flush();
        });
    }
}
