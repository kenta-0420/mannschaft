package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.payment.escrow.event.RecruitmentCancellationFeeChargeFailedEvent;
import com.mannschaft.app.payment.escrow.event.RecruitmentCancellationFeeChargedEvent;
import com.mannschaft.app.recruitment.event.RecruitmentCancellationFeeChargeRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F03.11.1 募集キャンセル料の徴収を起動するリスナ（設計書 §3.3 ステップ 2・§12-5）。
 *
 * <p>recruitment ドメインの {@link RecruitmentCancellationFeeChargeRequestedEvent} を受け、
 * {@link ConnectChargeService#settleCancellationFee} を呼ぶ。成否に応じて
 * {@link RecruitmentCancellationFeeChargedEvent} / {@link RecruitmentCancellationFeeChargeFailedEvent} を発火し、
 * 記録の状態更新は recruitment 側のリスナに委ねる（クロスドメイン直接呼び出しをしない・§3.1-3）。</p>
 *
 * <p>キャンセルそのものは利用者の意思表示であり、徴収の失敗で巻き戻してはならない（§3.1-2）。
 * そのため {@code AFTER_COMMIT} でのみ動き、例外を呼び出し元へ投げ返さない。徴収の失敗は握り潰さず、
 * 失敗イベントという観測可能な結節点として残す。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecruitmentCancellationFeeChargeListener {

    private final ConnectChargeService connectChargeService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * キャンセル料の徴収要求を受けて徴収を実行する。
     *
     * @param event 徴収要求イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めるとキャンセル料の請求が実行されず、DB 上は請求確定・決済は未実行という乖離が残る")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCancellationFeeChargeRequested(RecruitmentCancellationFeeChargeRequestedEvent event) {
        SettleCancellationFeeResult result;
        try {
            result = connectChargeService.settleCancellationFee(
                    EscrowSourceKind.RECRUITMENT, event.listingId(), event.participantId(),
                    event.feeAmount(), String.valueOf(event.cancellationRecordId()));
        } catch (RuntimeException e) {
            log.warn("F03.11.1 キャンセル料の徴収に失敗: recordId={}", event.cancellationRecordId(), e);
            eventPublisher.publishEvent(new RecruitmentCancellationFeeChargeFailedEvent(
                    event.cancellationRecordId(), e.getMessage()));
            return;
        }

        if (result.outcome() == SettleCancellationFeeOutcome.NOT_COLLECTIBLE) {
            // 与信が無い／使えないことは異常ではないが、記録としては未徴収である（§6.3）。
            log.info("F03.11.1 キャンセル料を徴収できなかった: recordId={}, 未徴収額={}",
                    event.cancellationRecordId(), result.uncollectedAmount());
            eventPublisher.publishEvent(new RecruitmentCancellationFeeChargeFailedEvent(
                    event.cancellationRecordId(), "徴収不能（与信が存在しないか使用できない）"));
            return;
        }

        eventPublisher.publishEvent(new RecruitmentCancellationFeeChargedEvent(
                event.cancellationRecordId(), result.stripeReference(), result.outcome()));
    }
}
