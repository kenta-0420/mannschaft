package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingChangePreviewEntity;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Billing Center PR6b-1 — A群 preview の消費（AC-6〜12 / AC-21・試練 red）。
 *
 * <p>「一回だけ・本人だけ・生きている間だけ」を、<b>DB の {@code consumed_at} と作られた
 * {@code billing_contract_changes} の件数</b>で測る。HTTP ステータスだけを見ると、実装が
 * 何もせず 409 を返しても緑になってしまうため、陽性対照（AC-7 前半の 202＋変更行1件）を対で置く。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6b-1 preview の消費（A群 AC-6〜12/AC-21・試練 red）")
class BillingChangePreviewConsumeRedIT extends AbstractBillingPlanChangeApiIT {

    private static final long TIMEOUT_SECONDS = 20L;

    @BeforeEach
    void setUp() {
        seedUpgradableContract("consume");
        // 3DS 要求の検体（pending_update あり）。ここでは「消費されたか」だけが関心。
        stubStripeApply("in_pr6b1_consume", "open", true);
    }

    @AfterEach
    void tearDown() {
        cleanupScope();
    }

    // ═════════ AC-7: 一回だけ消費できる ═════════

    @Test
    @DisplayName("AC-7: preview の消費は一回だけ（consumed_at の CAS）。二度目は 409 で変更行が増えない")
    void AC07_previewは一回だけ消費できる() throws Exception {
        UUID previewId = createPreviewId();

        // 陽性対照: 一度目は通り、変更行ができ、preview が消費済みになる。
        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isAccepted());
        assertThat(changesOf()).as("一度目で変更行が1件できる").hasSize(1);
        BillingChangePreviewEntity consumed = reloadPreview(previewId);
        assertThat(consumed.getConsumedAt()).as("consumed_at が立つ").isNotNull();
        assertThat(consumed.getVersion()).as("CAS は version を進める").isEqualTo(1L);

        // 二度目は消費済みで弾かれる。
        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_020"));
        assertThat(changesOf()).as("二度目で変更行が増えてはならない").hasSize(1);
    }

    // ═════════ AC-8: 並行消費（実DB・単一スレッドでは測れない） ═════════

    @Test
    @DisplayName("AC-8: 同じ preview を並行で消費しようとすると片方だけ成功する（実MySQL）")
    void AC08_並行消費は片方だけ成功() throws Exception {
        UUID previewId = createPreviewId();
        long version = contractVersion();

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Integer> statuses = runConcurrently(
                () -> attemptChange(barrier, previewId, version),
                () -> attemptChange(barrier, previewId, version));

        assertThat(statuses.stream().filter(s -> s == 202).count())
                .as("同時到達した2要求のうち成功は1件でなければならない").isEqualTo(1L);
        assertThat(statuses.stream().filter(s -> s == 409).count())
                .as("敗者は 409（500 で落ちるのは不可）").isEqualTo(1L);
        assertThat(changesOf()).as("変更行は1件だけ").hasSize(1);
        assertThat(pointerCount()).as("pointer も1件だけ（二重予約していない）").isEqualTo(1L);
    }

    // ═════════ AC-6 / AC-9: 期限（半開区間） ═════════

    @Test
    @DisplayName("AC-6: expires_at ちょうど（expires_at > now を満たさない）は失効として 409 になる")
    void AC06_期限ちょうどは失効() throws Exception {
        UUID previewId = createPreviewId();
        // 「ちょうど」＝ expires_at を現在時刻に一致させる。半開区間なら消費できない。
        mutatePreview(previewId, p -> p.setExpiresAt(Instant.now()));

        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_020"));
        assertThat(changesOf()).isEmpty();
        assertThat(reloadPreview(previewId).getConsumedAt())
                .as("失効した preview を消費済みにしてはならない").isNull();
    }

    @Test
    @DisplayName("AC-6(陽性対照): 期限内（+2分）の preview は消費できる（常時 409 でないこと）")
    void AC06b_期限内は消費できる() throws Exception {
        UUID previewId = createPreviewId();
        mutatePreview(previewId, p -> p.setExpiresAt(Instant.now().plusSeconds(120)));

        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isAccepted());
        assertThat(changesOf()).hasSize(1);
    }

    @Test
    @DisplayName("AC-9: 期限切れの preview は 409 ENTITLEMENT_020 で reason='PREVIEW_EXPIRED'")
    void AC09_期限切れは409_PREVIEW_EXPIRED() throws Exception {
        UUID previewId = createPreviewId();
        mutatePreview(previewId, p -> p.setExpiresAt(Instant.now().minusSeconds(60)));

        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_020"))
                .andExpect(jsonPath("$.error.details.reason").value("PREVIEW_EXPIRED"));
        assertThat(planChangeCalls("applyPlanChange"))
                .as("失効 preview で Stripe を呼ばない").isZero();
    }

    // ═════════ AC-10 / AC-11: IDOR ═════════

    @Test
    @DisplayName("AC-10: 他スコープの preview は 404（存在オラクルを残さない）")
    void AC10_他スコープのpreviewは404() throws Exception {
        ForeignScope foreign = insertForeignScope("consume-foreign");
        MvcResult foreignPreview = preview(foreign.userId(), foreign.contractId(), TO_PLAN_KEY, 0L, newKey())
                .andReturn();
        String foreignPreviewId = body(foreignPreview).path("data").path("previewId").asText(null);
        assertThat(foreignPreviewId)
                .as("前提: 別スコープでも見積りが作れる（作れないなら A群が未実装＝この赤は AC-1 由来）")
                .isNotNull();

        change(userId, contractId, UUID.fromString(foreignPreviewId), contractVersion(), newKey())
                .andExpect(status().isNotFound());
        assertThat(changesOf()).isEmpty();
        assertThat(planChangeCalls("applyPlanChange")).isZero();
    }

    @Test
    @DisplayName("AC-11: 同一スコープの別 actor が作った preview も 404（スコープ権限だけでは足りない）")
    void AC11_別actorのpreviewは404() throws Exception {
        UUID previewId = createPreviewId();
        Long otherActor = insertUser("consume-other-actor");
        // スコープはそのまま・actor だけ他人にする（actor 一致の検査だけを孤立させる）。
        mutatePreview(previewId, p -> p.setActorId(otherActor));

        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isNotFound());
        assertThat(changesOf()).isEmpty();
        assertThat(reloadPreview(previewId).getConsumedAt()).isNull();
    }

    // ═════════ AC-12: 契約 version の CAS ═════════

    @Test
    @DisplayName("AC-12: preview 発行後に contract の version が変わっていたら 409")
    void AC12_契約versionが変わったら409() throws Exception {
        UUID previewId = createPreviewId();
        bumpContractVersion();

        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isConflict());
        assertThat(changesOf()).as("CAS に落ちた要求は変更行を作らない").isEmpty();
        assertThat(planChangeCalls("applyPlanChange")).isZero();
    }

    // ═════════ AC-21: preview 作成後に target band が RETIRED ═════════

    @Test
    @DisplayName("AC-21: preview 作成後に target band が RETIRED になったら 409 CHANGE_CONFLICT（PREVIEW_EXPIRED ではない）")
    void AC21_band_RETIRED後のpreviewはCHANGE_CONFLICT() throws Exception {
        UUID previewId = createPreviewId();
        mutateBand(toBandId, band -> band.setStatus(BillingPriceVersionStatus.RETIRED));

        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_021"))
                .andExpect(jsonPath("$.error.details.reason").value("CHANGE_CONFLICT"));
        assertThat(changesOf()).isEmpty();
    }

    // ═════════ ヘルパ ═════════

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

    private void bumpContractVersion() {
        transactionTemplate.executeWithoutResult(tx -> {
            var contract = billingContractRepository.findByIdAndDeletedAtIsNull(contractId).orElseThrow();
            contract.setVersion(contract.getVersion() + 1);
            entityManager.flush();
        });
    }

    private int attemptChange(CyclicBarrier barrier, UUID previewId, long version) {
        try {
            barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return change(userId, contractId, previewId, version, newKey())
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException("並行試行に失敗した", e);
        }
    }

    private List<Integer> runConcurrently(Callable<Integer> first, Callable<Integer> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = executor.submit(first);
            Future<Integer> b = executor.submit(second);
            List<Integer> statuses = new ArrayList<>();
            statuses.add(a.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            statuses.add(b.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            return statuses;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }
}
