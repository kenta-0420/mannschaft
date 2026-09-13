package com.mannschaft.app.billing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 試練D（第4b隊）: <b>AC-81</b> — stale 判定のしきい値を<b>固定 Clock</b>で測る。
 *
 * <h2>なぜ固定 Clock なのか</h2>
 * <p>stale 判定は「経過時間」に依るため、実装が 引数なしの now() を直に呼んでいると
 * 境界（しきい値ちょうど）を決定論的に測れない。注入された {@link Clock} を使う実装だけが
 * 検証可能である。これは実装への制約であり、本テストがそれを固定する。</p>
 *
 * <p>境界の向きは<b>半開区間</b>に揃える（AC-24 / AC-37b と同じ流儀）。
 * {@code updatedAt < now - threshold} のときだけ stale とし、<b>しきい値ちょうどは stale にしない</b>。
 * 「ちょうど」を stale に含めると、Stripe 呼び出しがちょうどしきい値で応答した正常な operation を
 * 回収が横取りしうる。</p>
 *
 * <p>実 DB を要する走査そのもの（stale な行を実際に作って回収させる）は
 * {@code BillingContractOperationRecoveryIT} が担う。本クラスは判定式だけを純 UT で固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("試練D: AC-81 stale 判定のしきい値（固定Clock）")
class BillingOperationStaleThresholdTest {

    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock private BillingContractOperationRepository operationRepository;
    @Mock private ActiveBillingContractOperationPointerRepository pointerRepository;
    @Mock private BillingContractRepository billingContractRepository;
    @Mock private BillingContractOperationSagaService sagaService;
    @Mock private BillingPaymentGateway billingPaymentGateway;

    private BillingContractOperationRecoveryService recoveryService;

    @BeforeEach
    void setUp() {
        recoveryService = new BillingContractOperationRecoveryService(
                operationRepository, pointerRepository, billingContractRepository,
                sagaService, billingPaymentGateway, FIXED_CLOCK);
    }

    @Test
    @DisplayName("AC-81: 既定のしきい値は 5 分（Stripe 呼び出しのタイムアウトより十分に長く取る）")
    void defaultThresholdIsFiveMinutes() {
        assertThat(BillingContractOperationRecoveryService.DEFAULT_STALE_THRESHOLD)
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(recoveryService.staleThreshold())
                .isEqualTo(BillingContractOperationRecoveryService.DEFAULT_STALE_THRESHOLD);
    }

    @Test
    @DisplayName("AC-81: しきい値を超えて放置された CALLING_STRIPE は stale と判定される")
    void operationOlderThanThresholdIsStale() {
        assertThat(recoveryService.isStale(
                operation(BillingOperationStatus.CALLING_STRIPE, NOW.minus(Duration.ofMinutes(6)))))
                .isTrue();
    }

    @Test
    @DisplayName("AC-81: しきい値未満の進行中 operation は stale ではない（回収が横取りしない・陽性対照）")
    void operationYoungerThanThresholdIsNotStale() {
        assertThat(recoveryService.isStale(
                operation(BillingOperationStatus.CALLING_STRIPE, NOW.minus(Duration.ofMinutes(1)))))
                .as("進行中の正常な operation を回収が横取りしてはならない")
                .isFalse();
    }

    @Test
    @DisplayName("AC-81: しきい値ちょうどは stale ではない（半開区間・境界の向きを一意に固定する）")
    void exactlyAtThresholdIsNotStale() {
        assertThat(recoveryService.isStale(
                operation(BillingOperationStatus.CALLING_STRIPE, NOW.minus(Duration.ofMinutes(5)))))
                .isFalse();
    }

    @Test
    @DisplayName("AC-81: terminal な operation はどれだけ古くても stale ではない（回収対象は非終端のみ）")
    void terminalOperationIsNeverStale() {
        assertThat(recoveryService.isStale(
                operation(BillingOperationStatus.APPLIED, NOW.minus(Duration.ofDays(30)))))
                .isFalse();
        assertThat(recoveryService.isStale(
                operation(BillingOperationStatus.CANCELLED, NOW.minus(Duration.ofDays(30)))))
                .isFalse();
    }

    @Test
    @DisplayName("AC-81: RECONCILIATION_REQUIRED（検疫）は stale 走査の対象にしない（回収ではなく人手の reconcile が確定させる）")
    void quarantinedOperationIsNotStale() {
        assertThat(recoveryService.isStale(
                operation(BillingOperationStatus.RECONCILIATION_REQUIRED, NOW.minus(Duration.ofDays(3)))))
                .as("検疫は AC-8 の設計どおり pointer を保持したまま人手/reconcile を待つ")
                .isFalse();
    }

    private BillingContractOperationEntity operation(
            BillingOperationStatus status, Instant updatedAt) {
        BillingContractOperationEntity operation = BillingContractOperationEntity.builder()
                .contractId(UUID.randomUUID())
                .billingCustomerId(UUID.randomUUID())
                .kind(BillingOperationKind.CANCEL)
                .status(status)
                .step(BillingOperationStep.RECEIVED)
                .idempotencyKey(UUID.randomUUID().toString())
                .requestHash("f".repeat(64))
                .version(0L)
                .actorKind(BillingOperationActorKind.USER)
                .createdBy(1L)
                .createdAt(updatedAt)
                .updatedAt(updatedAt)
                .build();
        operation.setId(UUID.randomUUID());
        return operation;
    }
}
