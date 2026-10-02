package com.mannschaft.app.billing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 第11隊是正（AC-90）: {@code recoverStaleOperations()} が走査へ渡す {@code staleBefore} の
 * <b>計算そのもの</b>を、実時計を介さず決定的に固定する純UT。
 *
 * <h2>是正の経緯</h2>
 * <p>{@code BillingContractOperationRecoveryPlanChangeIT#exactlyFiveMinutesIsNotScanned} は
 * フィクスチャの {@code updated_at}（実時計 - 5分・秒切り捨て）とサービス内部の
 * {@code Instant.now(clock).minus(5分)}（実時計・別瞬間）という<b>2つの独立した実時計呼び出し</b>を
 * 突き合わせていたため、「ちょうど5分」の境界を検体として表現できていなかった
 * （フィクスチャ側の切り捨てで最大1秒過去へ倒れ、かつサービス側の計算は必ずそれより後に走るため、
 * 実際には常に5分を超過していた）。</p>
 *
 * <p>本クラスは {@link Clock#fixed} で時刻を完全に止め、{@code recoverStaleOperations()} が
 * リポジトリへ渡す {@code staleBefore} 引数を {@link ArgumentCaptor} で捕まえて
 * {@code NOW.minus(Duration.ofMinutes(5))} と<b>厳密一致</b>することを主張する
 * （{@code BillingOperationRecoveryPeriodEndTest} と同じ流儀: Mockito モック＋
 * {@code ReflectionTestUtils} でフィールド注入の協調子を差す）。</p>
 *
 * <p>DB 述語自体の半開区間（{@code <} であって {@code <=} ではない）は
 * {@code BillingContractOperationRepositoryStaleScanBoundaryIT}（実MySQL）が別途固定する。
 * 本クラスと合わせて「しきい値の計算」と「DBが述語をどう評価するか」を分解して検体化している。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("第11隊是正: recoverStaleOperations の staleBefore 計算（AC-90）")
class BillingContractOperationRecoveryStaleBeforeThresholdTest {

    private static final Instant NOW = Instant.parse("2026-09-17T09:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock private BillingContractOperationRepository operationRepository;
    @Mock private ActiveBillingContractOperationPointerRepository pointerRepository;
    @Mock private BillingContractRepository billingContractRepository;
    @Mock private BillingContractOperationSagaService sagaService;
    @Mock private BillingPaymentGateway billingPaymentGateway;
    @Mock private PlatformTransactionManager transactionManager;

    @Captor private ArgumentCaptor<Instant> staleBeforeCaptor;

    private BillingContractOperationRecoveryService recoveryService;

    @BeforeEach
    void setUp() {
        recoveryService = new BillingContractOperationRecoveryService(
                operationRepository, pointerRepository, billingContractRepository,
                sagaService, billingPaymentGateway, FIXED_CLOCK);
        // コンストラクタ引数は試練Dの発注書で6個に固定されているため、DB を触らない
        // 本テストでも transactionTemplate をフィールド注入で差しておく
        // （recoverStaleOperations 自体は走査結果が空なら参照しないが、他協調子と揃える）。
        ReflectionTestUtils.setField(recoveryService, "transactionTemplate",
                new TransactionTemplate(transactionManager));

        given(operationRepository.findByStatusInAndDeletedAtIsNullAndUpdatedAtLessThan(
                any(), any(), any(Pageable.class)))
                .willReturn(List.of());
    }

    @Test
    @DisplayName("AC-90: staleBefore は NOW - 5分（DEFAULT_STALE_THRESHOLD）と厳密一致する")
    void staleBeforeIsExactlyNowMinusFiveMinutes() {
        recoveryService.recoverStaleOperations();

        verify(operationRepository).findByStatusInAndDeletedAtIsNullAndUpdatedAtLessThan(
                any(), staleBeforeCaptor.capture(), any(Pageable.class));
        assertThat(staleBeforeCaptor.getValue())
                .isEqualTo(NOW.minus(Duration.ofMinutes(5)));
        assertThat(staleBeforeCaptor.getValue())
                .as("既定しきい値の定数そのものから計算されている")
                .isEqualTo(NOW.minus(
                        BillingContractOperationRecoveryService.DEFAULT_STALE_THRESHOLD));
    }

    @Test
    @DisplayName("AC-90: 走査対象の status 集合は CREATED / CALLING_STRIPE の2値である（RECONCILIATION_REQUIRED を含めない・AC-8）")
    void scanStatusesAreCreatedAndCallingStripeOnly() {
        recoveryService.recoverStaleOperations();

        var statusesCaptor = org.mockito.ArgumentCaptor
                .forClass(Collection.class);
        verify(operationRepository).findByStatusInAndDeletedAtIsNullAndUpdatedAtLessThan(
                statusesCaptor.capture(), any(), any(Pageable.class));
        assertThat(statusesCaptor.getValue())
                .containsExactlyInAnyOrder(
                        BillingOperationStatus.CREATED, BillingOperationStatus.CALLING_STRIPE);
    }
}
