package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.payment.escrow.event.RecruitmentCancellationFeeChargeFailedEvent;
import com.mannschaft.app.payment.escrow.event.RecruitmentCancellationFeeChargedEvent;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * F03.11.1 徴収結果をキャンセル記録へ反映するリスナ（設計書 §3.3 ステップ 6・§12-6）。
 *
 * <p>payment ドメインから返ってきた成否のイベントを受け、記録を {@code markPaid(paymentId)} または
 * {@code markFailed()} へ移す。トランザクションは自ドメイン内に閉じた短命なものとする。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecruitmentCancellationFeeResultListener {

    private final RecruitmentCancellationRecordRepository cancellationRecordRepository;

    /**
     * 徴収成功を受けて記録を PAID にする。
     *
     * <p>{@code paymentId} には Stripe の参照 ID が入る。部分キャプチャなら PaymentIntent ID（{@code pi_...}）、
     * 差額返金なら Refund ID（{@code re_...}）であり、接頭辞でどちらの経路で徴収したかが判別できる（§3.7）。</p>
     *
     * @param event 徴収成功イベント
     */
    @EventListener
    @Transactional
    public void onCancellationFeeCharged(RecruitmentCancellationFeeChargedEvent event) {
        find(event.cancellationRecordId()).ifPresent(record -> {
            record.markPaid(event.stripeReference());
            cancellationRecordRepository.save(record);
            log.info("F03.11.1 キャンセル料の徴収を記録に反映 PAID: recordId={}, outcome={}",
                    event.cancellationRecordId(), event.outcome());
        });
    }

    /**
     * 徴収失敗を受けて記録を FAILED にする。
     *
     * <p>徴収できなかったことは異常ではなく想定内の状態でもある（与信が無い等・§6.3）。
     * 例外にせず FAILED として正直に記録し、リトライバッチの対象に載せる。</p>
     *
     * @param event 徴収失敗イベント
     */
    @EventListener
    @Transactional
    public void onCancellationFeeChargeFailed(RecruitmentCancellationFeeChargeFailedEvent event) {
        find(event.cancellationRecordId()).ifPresent(record -> {
            record.markFailed();
            cancellationRecordRepository.save(record);
            log.info("F03.11.1 キャンセル料の徴収失敗を記録に反映 FAILED: recordId={}, reason={}",
                    event.cancellationRecordId(), event.reason());
        });
    }

    private Optional<RecruitmentCancellationRecordEntity> find(Long recordId) {
        Optional<RecruitmentCancellationRecordEntity> record = cancellationRecordRepository.findById(recordId);
        if (record.isEmpty()) {
            log.warn("F03.11.1 徴収結果の反映先となるキャンセル記録が見つからない: recordId={}", recordId);
        }
        return record;
    }
}
