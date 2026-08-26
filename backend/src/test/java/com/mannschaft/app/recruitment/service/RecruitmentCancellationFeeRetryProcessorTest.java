package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.SettleCancellationFeeOutcome;
import com.mannschaft.app.payment.escrow.SettleCancellationFeeResult;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F03.11.1 キャンセル料徴収リトライの 1 件処理（{@link RecruitmentCancellationFeeRetryProcessor}）の試練。
 *
 * <p>設計書 §5.4・§5.5・§7.4 の受け入れ条件 AC-5 / AC-6 / AC-7 / AC-8 / AC-17 を担う。</p>
 *
 * <p>初回徴収とリトライで別ロジックを持たない（同じ引き当て・同じ冪等キー・同じ状態遷移）ことを
 * 「同じ入口 {@link ConnectChargeService#settleCancellationFee} を同じ引数で呼ぶ」ことで観測する。</p>
 *
 * <p>本クラスは実装より前に書かれた red テストである。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F03.11.1 RecruitmentCancellationFeeRetryProcessor 試練")
class RecruitmentCancellationFeeRetryProcessorTest {

    @Mock private RecruitmentCancellationRecordRepository cancellationRecordRepository;
    @Mock private ConnectChargeService connectChargeService;

    private static final Long LISTING_ID = 100L;

    private RecruitmentCancellationFeeRetryProcessor processor() {
        return new RecruitmentCancellationFeeRetryProcessor(cancellationRecordRepository, connectChargeService);
    }

    private RecruitmentCancellationRecordEntity failedRecord(Long id, int retryCount) {
        RecruitmentCancellationRecordEntity r = RecruitmentCancellationRecordEntity.builder()
                .participantId(200L + id)
                .listingId(LISTING_ID)
                .userId(1L)
                .teamId(10L)
                .cancelledAt(LocalDateTime.now())
                .cancelledBy(1L)
                .cancelSource(CancellationSource.USER)
                .hoursBeforeStart(6)
                .feeAmount(3_000)
                .paymentStatus(CancellationPaymentStatus.FAILED)
                .build();
        setField(r, "id", id);
        setField(r, "paymentRetryCount", retryCount);
        return r;
    }

    @Test
    @DisplayName("AC-5: リトライは実際に決済 API を呼ぶ（スタブではない）")
    void ac5_retryActuallyCallsSettlement() {
        RecruitmentCancellationFeeRetryProcessor proc = processor();
        RecruitmentCancellationRecordEntity record = failedRecord(1L, 0);
        given(cancellationRecordRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(connectChargeService.settleCancellationFee(
                any(EscrowSourceKind.class), anyLong(), anyLong(), anyLong(), anyString()))
                .willReturn(new SettleCancellationFeeResult(
                        SettleCancellationFeeOutcome.CAPTURED_PARTIAL, "pi_abc", 0L));

        proc.processOne(record);

        // 引き当ての三つ組・料金・記録 ID 由来の冪等キーの素を、初回徴収と同じ形で渡す（§3.4・§7.1）。
        verify(connectChargeService).settleCancellationFee(
                EscrowSourceKind.RECRUITMENT, LISTING_ID, record.getParticipantId(), 3_000L, "1");
    }

    @Test
    @DisplayName("AC-6: リトライ成功で PAID へ遷移し payment_id に Stripe 参照が入る")
    void ac6_successfulRetryMovesToPaid() {
        RecruitmentCancellationFeeRetryProcessor proc = processor();
        RecruitmentCancellationRecordEntity record = failedRecord(1L, 1);
        given(cancellationRecordRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(connectChargeService.settleCancellationFee(
                any(EscrowSourceKind.class), anyLong(), anyLong(), anyLong(), anyString()))
                .willReturn(new SettleCancellationFeeResult(
                        SettleCancellationFeeOutcome.CAPTURED_PARTIAL, "pi_abc", 0L));

        boolean success = proc.processOne(record);

        assertThat(success).isTrue();
        assertThat(record.getPaymentStatus()).isEqualTo(CancellationPaymentStatus.PAID);
        assertThat(record.getPaymentId()).isEqualTo("pi_abc");
    }

    @Test
    @DisplayName("AC-7: リトライ上限3回到達で終端状態 UNCOLLECTIBLE へ遷移する（以後リトライの網から外れる）")
    void ac7_thirdFailureMovesToUncollectible() {
        RecruitmentCancellationFeeRetryProcessor proc = processor();
        // retryCount=2 の行を処理して失敗すると 3 に達する（§11.1 AC-7 と AC-15 は互いの裏表）。
        RecruitmentCancellationRecordEntity record = failedRecord(1L, 2);
        given(cancellationRecordRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(connectChargeService.settleCancellationFee(
                any(EscrowSourceKind.class), anyLong(), anyLong(), anyLong(), anyString()))
                .willReturn(new SettleCancellationFeeResult(
                        SettleCancellationFeeOutcome.NOT_COLLECTIBLE, null, 3_000L));

        boolean success = proc.processOne(record);

        assertThat(success).isFalse();
        assertThat(record.getPaymentRetryCount()).isEqualTo(3);
        // FAILED のままだと「上限に達したが状態としては拾われも解決もされない」宙吊りが残る（§5.4）。
        assertThat(record.getPaymentStatus()).isEqualTo(CancellationPaymentStatus.UNCOLLECTIBLE);
    }

    @Test
    @DisplayName("AC-7(境界): 上限未満の失敗は FAILED のまま（打ち切りは 3 回到達時のみ）")
    void ac7_failureBelowLimitStaysFailed() {
        RecruitmentCancellationFeeRetryProcessor proc = processor();
        RecruitmentCancellationRecordEntity record = failedRecord(1L, 1);
        given(cancellationRecordRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(connectChargeService.settleCancellationFee(
                any(EscrowSourceKind.class), anyLong(), anyLong(), anyLong(), anyString()))
                .willReturn(new SettleCancellationFeeResult(
                        SettleCancellationFeeOutcome.NOT_COLLECTIBLE, null, 3_000L));

        proc.processOne(record);

        assertThat(record.getPaymentRetryCount()).isEqualTo(2);
        assertThat(record.getPaymentStatus()).isEqualTo(CancellationPaymentStatus.FAILED);
    }

    @Test
    @DisplayName("AC-8: 1件の失敗が他件のコミットを巻き戻さない（チャンク内で独立して処理される）")
    void ac8_oneFailureDoesNotAffectOtherRecords() {
        RecruitmentCancellationFeeRetryProcessor proc = processor();
        RecruitmentCancellationRecordEntity boom = failedRecord(1L, 0);
        RecruitmentCancellationRecordEntity ok = failedRecord(2L, 0);
        given(cancellationRecordRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(connectChargeService.settleCancellationFee(
                any(EscrowSourceKind.class), anyLong(), anyLong(), anyLong(), anyString()))
                .willThrow(new IllegalStateException("Stripe 側の一時障害"))
                .willReturn(new SettleCancellationFeeResult(
                        SettleCancellationFeeOutcome.CAPTURED_PARTIAL, "pi_ok", 0L));

        int successCount = proc.processChunk(List.of(boom, ok));

        // 1 件分の処理は REQUIRES_NEW の独立したトランザクションであり、巻き添えにしない。
        assertThat(successCount).isEqualTo(1);
        assertThat(ok.getPaymentStatus()).isEqualTo(CancellationPaymentStatus.PAID);
        assertThat(boom.getPaymentStatus()).isEqualTo(CancellationPaymentStatus.FAILED);
    }

    @Test
    @DisplayName("AC-17: キャプチャ成功後の DB 更新失敗でも、リトライで同一の冪等キーが渡り二重課金しない")
    void ac17_dbFailureAfterCapture_retryUsesSameIdempotencyReference() {
        RecruitmentCancellationFeeRetryProcessor proc = processor();
        RecruitmentCancellationRecordEntity record = failedRecord(1L, 0);
        given(connectChargeService.settleCancellationFee(
                any(EscrowSourceKind.class), anyLong(), anyLong(), anyLong(), anyString()))
                .willReturn(new SettleCancellationFeeResult(
                        SettleCancellationFeeOutcome.CAPTURED_PARTIAL, "pi_abc", 0L));
        // 1 回目: Stripe は成功したが DB 更新で落ちる。2 回目: 復旧している。
        given(cancellationRecordRepository.save(any()))
                .willThrow(new IllegalStateException("DB 接続エラー"))
                .willAnswer(inv -> inv.getArgument(0));

        proc.processOne(record);
        boolean second = proc.processOne(record);

        ArgumentCaptor<String> refCaptor = ArgumentCaptor.forClass(String.class);
        verify(connectChargeService, times(2)).settleCancellationFee(
                any(EscrowSourceKind.class), anyLong(), anyLong(), anyLong(), refCaptor.capture());
        // 「失敗時はキーを変える」ことをしてはならない（§7.4）。変えた瞬間に二重課金しうる。
        assertThat(refCaptor.getAllValues()).containsExactly("1", "1");
        // 呼び出し回数だけでなく、最終的な状態でも整合が回復していることを確かめる。
        assertThat(second).isTrue();
        assertThat(record.getPaymentStatus()).isEqualTo(CancellationPaymentStatus.PAID);
    }

    // ==========================================================
    // ヘルパー
    // ==========================================================

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
