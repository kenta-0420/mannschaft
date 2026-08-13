package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.payment.escrow.SettleCancellationFeeOutcome;
import com.mannschaft.app.payment.escrow.event.RecruitmentCancellationFeeChargeFailedEvent;
import com.mannschaft.app.payment.escrow.event.RecruitmentCancellationFeeChargedEvent;
import com.mannschaft.app.recruitment.CancellationPaymentStatus;
import com.mannschaft.app.recruitment.CancellationSource;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * F03.11.1 徴収結果のキャンセル記録への反映（{@link RecruitmentCancellationFeeResultListener}）の試練。
 *
 * <p>受け入れ条件 AC-1（PAID＋payment_id の記録）・AC-11 / AC-12（徴収不能でも FAILED に落として 500 にしない）
 * の記録側を担う。</p>
 *
 * <p>本クラスは実装より前に書かれた red テストである。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F03.11.1 RecruitmentCancellationFeeResultListener 試練")
class RecruitmentCancellationFeeResultListenerTest {

    @Mock private RecruitmentCancellationRecordRepository cancellationRecordRepository;

    private static final Long RECORD_ID = 77L;

    private RecruitmentCancellationFeeResultListener listener() {
        return new RecruitmentCancellationFeeResultListener(cancellationRecordRepository);
    }

    private void givenRecord(CancellationPaymentStatus status) {
        RecruitmentCancellationRecordEntity r = RecruitmentCancellationRecordEntity.builder()
                .participantId(200L)
                .listingId(100L)
                .userId(1L)
                .teamId(10L)
                .cancelledAt(LocalDateTime.now())
                .cancelledBy(1L)
                .cancelSource(CancellationSource.USER)
                .hoursBeforeStart(6)
                .feeAmount(3_000)
                .paymentStatus(status)
                .build();
        setField(r, "id", RECORD_ID);
        given(cancellationRecordRepository.findById(RECORD_ID)).willReturn(Optional.of(r));
        given(cancellationRecordRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("AC-1: 部分キャプチャの成功で記録が PAID になり payment_id に PaymentIntent ID が入る")
    void ac1_capturedPartial_marksPaidWithPaymentIntentId() {
        RecruitmentCancellationFeeResultListener listener = listener();
        givenRecord(CancellationPaymentStatus.PENDING);

        listener.onCancellationFeeCharged(new RecruitmentCancellationFeeChargedEvent(
                RECORD_ID, "pi_abc", SettleCancellationFeeOutcome.CAPTURED_PARTIAL));

        ArgumentCaptor<RecruitmentCancellationRecordEntity> captor =
                ArgumentCaptor.forClass(RecruitmentCancellationRecordEntity.class);
        verify(cancellationRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getPaymentStatus()).isEqualTo(CancellationPaymentStatus.PAID);
        assertThat(captor.getValue().getPaymentId()).isEqualTo("pi_abc");
    }

    @Test
    @DisplayName("AC-22: 差額返金の成功で payment_id に Refund ID が入る（接頭辞で経路が判別できる）")
    void ac22_refundedDifference_marksPaidWithRefundId() {
        RecruitmentCancellationFeeResultListener listener = listener();
        givenRecord(CancellationPaymentStatus.PENDING);

        listener.onCancellationFeeCharged(new RecruitmentCancellationFeeChargedEvent(
                RECORD_ID, "re_abc", SettleCancellationFeeOutcome.REFUNDED_DIFFERENCE));

        ArgumentCaptor<RecruitmentCancellationRecordEntity> captor =
                ArgumentCaptor.forClass(RecruitmentCancellationRecordEntity.class);
        verify(cancellationRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getPaymentStatus()).isEqualTo(CancellationPaymentStatus.PAID);
        assertThat(captor.getValue().getPaymentId()).isEqualTo("re_abc");
    }

    @Test
    @DisplayName("AC-11/AC-12: 徴収不能の通知で記録は FAILED になる（例外にしない）")
    void ac11ac12_failure_marksFailed() {
        RecruitmentCancellationFeeResultListener listener = listener();
        givenRecord(CancellationPaymentStatus.PENDING);

        listener.onCancellationFeeChargeFailed(
                new RecruitmentCancellationFeeChargeFailedEvent(RECORD_ID, "与信が存在しない"));

        ArgumentCaptor<RecruitmentCancellationRecordEntity> captor =
                ArgumentCaptor.forClass(RecruitmentCancellationRecordEntity.class);
        verify(cancellationRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getPaymentStatus()).isEqualTo(CancellationPaymentStatus.FAILED);
    }

    private static void setField(Object entity, String name, Object value) {
        Class<?> clazz = entity.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(entity, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("フィールドが見つからない: " + name);
    }
}
