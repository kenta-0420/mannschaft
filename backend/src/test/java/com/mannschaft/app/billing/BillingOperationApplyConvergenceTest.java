package com.mannschaft.app.billing;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Codex 検分 <b>P1-2</b>: 通常の tx2 と {@code customer.subscription.updated} 由来の回収が
 * <b>どちらが先着しても成功へ収束する</b>ことを固定する。
 *
 * <h2>何が壊れていたのか（利用者から見た症状）</h2>
 * <p>Stripe への同期呼び出しが成功した直後に {@code customer.subscription.updated} が届くと、
 * 停止窓の回収が通常経路の {@link BillingContractOperationSagaService#applyAndFinalize} より先に
 * 同じ operation を {@code APPLIED} へ確定させる。その後に通常経路が
 * {@code APPLIED -> APPLIED} を投げると、状態機械が自己遷移として拒否し例外になる（AC-10）。</p>
 *
 * <p>つまり<b>解約は Stripe でも DB でも成立しているのに、利用者には「解約できませんでした」が返り、
 * 冪等台帳にも失敗が記録される</b>。最悪の食い違いであり、再送すると今度は
 * 「pointer が無い／既に cancelled_at がある」で別の 409 に化ける。</p>
 *
 * <h2>収束の測り方（この2本が対である理由）</h2>
 * <p>先着順の両方を測る。<b>回収が先</b>（{@link #convergesWhenRecoveryWonFirst()}）では
 * 例外を投げず反映の戻り値を返し、pointer を取り残さないこと。<b>通常経路が先</b>
 * （{@link #normalPathStillTransitionsWhenItWinsFirst()}）では従来どおり
 * {@code CALLING_STRIPE -> APPLIED} の遷移が起きること（陽性対照。収束を口実に
 * 「常に何もしない」実装になっていないことの裏取り）。</p>
 *
 * <p>加えて、<b>結末が違うとき</b>（{@code FAILED} 確定済み）は黙って成功にせず例外のままである
 * ことを陰性対照として置く。ここを一緒くたに握り潰すと、失敗した operation が成功として
 * 利用者へ返るという逆向きの事故になる。</p>
 *
 * <h2>順序（Codex 再検分 P1）</h2>
 * <p>結末の確認は {@code reflection} より<b>前</b >でなければならない。後ろに置くと、収束する
 * 場合でも反映だけは実行されて commit され、その後に成立した新しい利用者操作を古い反映が
 * 上書きする。ここでは<b>反映がそもそも実行されないこと</b>を実行フラグで直接測る
 * （「結果的に呼ばれない」ではなく「呼ばれない」を固定する）。上書きが起きないという
 * <b>成果物</b>そのものは実 DB の {@code BillingContractOperationDelayedApplyIT} が測る。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Codex P1-2: tx2 と回収の先着競合は成功へ収束する")
class BillingOperationApplyConvergenceTest {

    private static final UUID OPERATION_ID =
            UUID.fromString("0199ab99-9999-9999-8999-999999999999");
    private static final UUID CONTRACT_ID =
            UUID.fromString("0199abaa-aaaa-aaaa-8aaa-aaaaaaaaaaaa");
    private static final String REFLECTION_RESULT = "applied-view";
    /** 収束時に返す「いま DB にある姿」。反映の戻り値と別物にして取り違えを検出する。 */
    private static final String CONVERGED_READ_RESULT = "current-truth-view";

    @Mock private BillingContractOperationRepository operationRepository;
    @Mock private ActiveBillingContractOperationPointerRepository pointerRepository;
    @Mock private EntityManager entityManager;
    @Mock private BillingCustomerLinkPort billingCustomerLinkPort;
    @Mock private PlatformTransactionManager transactionManager;

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC);

    private BillingContractOperationSagaService sagaService;

    @BeforeEach
    void setUp() {
        sagaService = new BillingContractOperationSagaService(
                operationRepository, pointerRepository, entityManager,
                billingCustomerLinkPort, transactionManager, FIXED_CLOCK);
    }

    @Test
    @DisplayName("P1-2: 回収が先着して APPLIED 済みでも、通常経路は例外にならず成功を返す"
            + "（解約は成立しているのに失敗を返さない）")
    void convergesWhenRecoveryWonFirst() {
        givenLockedOperation(BillingOperationStatus.APPLIED);

        String result = sagaService.applyAndFinalize(
                OPERATION_ID, () -> REFLECTION_RESULT, () -> CONVERGED_READ_RESULT);

        assertThat(result)
                .as("収束時に返すのは『自分が書いたはずの姿』ではなく、いま DB にある真実である")
                .isEqualTo(CONVERGED_READ_RESULT);
    }

    @Test
    @DisplayName("P1: 収束する場合は反映処理をそもそも実行しない"
            + "（後から成立した利用者操作を古い反映で上書きしない・順序の要）")
    void doesNotRunReflectionWhenConverging() {
        givenLockedOperation(BillingOperationStatus.APPLIED);
        AtomicBoolean reflectionRan = new AtomicBoolean(false);

        sagaService.applyAndFinalize(OPERATION_ID,
                () -> {
                    reflectionRan.set(true);
                    return REFLECTION_RESULT;
                },
                () -> CONVERGED_READ_RESULT);

        assertThat(reflectionRan)
                .as("結末の確認を反映の後ろに置くと、収束時も反映だけ実行され撤回が消える")
                .isFalse();
    }

    @Test
    @DisplayName("P1: 収束時の読み取りを与えずに収束状況へ入ったら、黙って成功にせず例外にする"
            + "（2引数版は収束前の挙動のまま）")
    void twoArgVariantStillFailsWhenConverged() {
        givenLockedOperation(BillingOperationStatus.APPLIED);

        assertThatThrownBy(() -> sagaService.applyAndFinalize(OPERATION_ID, () -> REFLECTION_RESULT))
                .as("何を返すべきか宣言していない呼び出し元が、成功を騙ってはならない")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("P1-2: 収束した場合も pointer は取り残さない（解放は冪等な物理 DELETE）")
    void releasesPointerWhenConverging() {
        givenLockedOperation(BillingOperationStatus.APPLIED);

        sagaService.applyAndFinalize(
                OPERATION_ID, () -> REFLECTION_RESULT, () -> CONVERGED_READ_RESULT);

        verify(pointerRepository).hardDeleteByContractIdAndOperationId(CONTRACT_ID, OPERATION_ID);
    }

    @Test
    @DisplayName("P1-2: 収束した場合は status を触らない（自己遷移 APPLIED -> APPLIED を状態機械へ投げない）")
    void doesNotAttemptSelfTransitionWhenConverging() {
        givenLockedOperation(BillingOperationStatus.APPLIED);

        sagaService.applyAndFinalize(
                OPERATION_ID, () -> REFLECTION_RESULT, () -> CONVERGED_READ_RESULT);

        verify(operationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("P1-2: 通常経路が先着したときは従来どおり CALLING_STRIPE -> APPLIED へ遷移する"
            + "（陽性対照。収束を口実に何もしない実装ではない）")
    void normalPathStillTransitionsWhenItWinsFirst() {
        BillingContractOperationEntity operation = givenLockedOperation(
                BillingOperationStatus.CALLING_STRIPE);
        given(operationRepository.findByIdAndDeletedAtIsNull(OPERATION_ID))
                .willReturn(Optional.of(operation));
        given(operationRepository.saveAndFlush(any())).willAnswer(i -> i.getArgument(0));

        AtomicBoolean reflectionRan = new AtomicBoolean(false);

        String result = sagaService.applyAndFinalize(OPERATION_ID,
                () -> {
                    reflectionRan.set(true);
                    return REFLECTION_RESULT;
                },
                () -> CONVERGED_READ_RESULT);

        assertThat(reflectionRan).as("自分が先着したのだから反映は実行される").isTrue();
        assertThat(result).isEqualTo(REFLECTION_RESULT);
        assertThat(operation.getStatus()).isEqualTo(BillingOperationStatus.APPLIED);
        verify(operationRepository).saveAndFlush(operation);
        verify(pointerRepository).hardDeleteByContractIdAndOperationId(CONTRACT_ID, OPERATION_ID);
    }

    @Test
    @DisplayName("P1-2: 別の結末（FAILED 確定済み）は黙って成功にせず例外のままにする（陰性対照）")
    void doesNotSwallowDifferentTerminalOutcome() {
        BillingContractOperationEntity operation = givenLockedOperation(
                BillingOperationStatus.FAILED);
        given(operationRepository.findByIdAndDeletedAtIsNull(OPERATION_ID))
                .willReturn(Optional.of(operation));

        assertThatThrownBy(() -> sagaService.applyAndFinalize(OPERATION_ID, () -> REFLECTION_RESULT))
                .as("結末が違うものを成功として返してはならない")
                .isInstanceOf(IllegalStateException.class);
    }

    /** {@code SELECT ... FOR UPDATE} で読まれる operation 行を与える。 */
    private BillingContractOperationEntity givenLockedOperation(BillingOperationStatus status) {
        BillingContractOperationEntity operation = BillingContractOperationEntity.builder()
                .contractId(CONTRACT_ID)
                .billingCustomerId(UUID.randomUUID())
                .kind(BillingOperationKind.CANCEL)
                .status(status)
                .step(BillingOperationStep.STRIPE_CANCEL_SUBSCRIPTION)
                .idempotencyKey(OPERATION_ID.toString())
                .requestHash("0".repeat(64))
                .version(0L)
                .actorKind(BillingOperationActorKind.USER)
                .createdBy(1L)
                .createdAt(Instant.parse("2026-09-12T00:00:00Z"))
                .updatedAt(Instant.parse("2026-09-12T00:00:00Z"))
                .build();
        operation.setId(OPERATION_ID);
        given(entityManager.find(
                eq(BillingContractOperationEntity.class), eq(OPERATION_ID),
                eq(LockModeType.PESSIMISTIC_WRITE)))
                .willReturn(operation);
        return operation;
    }
}
