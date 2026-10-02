package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.payment.escrow.event.RecruitmentCancellationFeeChargeFailedEvent;
import com.mannschaft.app.payment.escrow.event.RecruitmentCancellationFeeChargedEvent;
import com.mannschaft.app.recruitment.event.RecruitmentCancellationFeeChargeRequestedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * F03.11.1 徴収リスナ（{@link RecruitmentCancellationFeeChargeListener}）の試練。
 *
 * <p>受け入れ条件 AC-4 の構造面——「決済はキャンセルのトランザクションの外で走り、その失敗で
 * キャンセルを巻き戻さない」——を担う。これは購読の位相（{@code AFTER_COMMIT}）で担保される。</p>
 *
 * <p>本クラスは実装より前に書かれた red テストである。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F03.11.1 RecruitmentCancellationFeeChargeListener 試練")
class RecruitmentCancellationFeeChargeListenerTest {

    @Mock private ConnectChargeService connectChargeService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private static final Long RECORD_ID = 77L;
    private static final Long LISTING_ID = 100L;
    private static final Long PARTICIPANT_ID = 200L;

    private RecruitmentCancellationFeeChargeListener listener() {
        return new RecruitmentCancellationFeeChargeListener(connectChargeService, eventPublisher);
    }

    private RecruitmentCancellationFeeChargeRequestedEvent requestedEvent() {
        return new RecruitmentCancellationFeeChargeRequestedEvent(
                RECORD_ID, LISTING_ID, PARTICIPANT_ID, 1L, 3_000);
    }

    @Test
    @DisplayName("AC-4: 徴収はキャンセルのコミット後に非同期で走る（AFTER_COMMIT + 非同期で購読する）")
    void ac4_listenerSubscribesAfterCommitAndAsynchronously() throws NoSuchMethodException {
        Method handler = RecruitmentCancellationFeeChargeListener.class.getMethod(
                "onCancellationFeeChargeRequested", RecruitmentCancellationFeeChargeRequestedEvent.class);

        TransactionalEventListener subscription = handler.getAnnotation(TransactionalEventListener.class);
        assertThat(subscription)
                .as("キャンセルのトランザクション内で決済を走らせてはならない")
                .isNotNull();
        assertThat(subscription.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(handler.getAnnotation(Async.class))
                .as("利用者のキャンセル要求を Stripe の所要時間で待たせない")
                .isNotNull();
    }

    @Test
    @DisplayName("AC-4: 徴収成功で徴収成功イベントを発火する（記録の更新は recruitment 側の責務）")
    void ac4_successPublishesChargedEvent() {
        RecruitmentCancellationFeeChargeListener listener = listener();
        given(connectChargeService.settleCancellationFee(
                any(EscrowSourceKind.class), anyLong(), anyLong(), anyLong(), anyString()))
                .willReturn(new SettleCancellationFeeResult(
                        SettleCancellationFeeOutcome.CAPTURED_PARTIAL, "pi_abc", 0L));

        listener.onCancellationFeeChargeRequested(requestedEvent());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(RecruitmentCancellationFeeChargedEvent.class);
        RecruitmentCancellationFeeChargedEvent charged = (RecruitmentCancellationFeeChargedEvent) captor.getValue();
        assertThat(charged.cancellationRecordId()).isEqualTo(RECORD_ID);
        assertThat(charged.stripeReference()).isEqualTo("pi_abc");
        assertThat(charged.outcome()).isEqualTo(SettleCancellationFeeOutcome.CAPTURED_PARTIAL);
    }

    @Test
    @DisplayName("AC-4: Stripe が失敗しても例外を投げ返さず失敗イベントを発火する（キャンセルは巻き戻らない）")
    void ac4_failurePublishesFailedEventWithoutRethrowing() {
        RecruitmentCancellationFeeChargeListener listener = listener();
        given(connectChargeService.settleCancellationFee(
                any(EscrowSourceKind.class), anyLong(), anyLong(), anyLong(), anyString()))
                .willThrow(new IllegalStateException("Stripe 側の障害"));

        listener.onCancellationFeeChargeRequested(requestedEvent());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(RecruitmentCancellationFeeChargeFailedEvent.class);
        // 失敗は握り潰さず、観測可能・後続でアクション可能な結節点として残す。
        assertThat(((RecruitmentCancellationFeeChargeFailedEvent) captor.getValue()).cancellationRecordId())
                .isEqualTo(RECORD_ID);
    }

    @Test
    @DisplayName("AC-11/AC-12: 徴収不能でも失敗イベントに落とし、例外を外へ漏らさない")
    void notCollectible_publishesFailedEvent() {
        RecruitmentCancellationFeeChargeListener listener = listener();
        given(connectChargeService.settleCancellationFee(
                any(EscrowSourceKind.class), anyLong(), anyLong(), anyLong(), anyString()))
                .willReturn(new SettleCancellationFeeResult(
                        SettleCancellationFeeOutcome.NOT_COLLECTIBLE, null, 3_000L));

        listener.onCancellationFeeChargeRequested(requestedEvent());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(RecruitmentCancellationFeeChargeFailedEvent.class);
    }
}
