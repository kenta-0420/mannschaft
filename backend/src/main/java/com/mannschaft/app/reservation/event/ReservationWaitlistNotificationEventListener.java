package com.mannschaft.app.reservation.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.reservation.service.ReservationWaitlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 予約枠の空き復帰時に、キャンセル待ち登録者へ一斉通知するリスナー（F03.4.5 §6.1）。
 *
 * <p>{@link ReservationSlotReopenedEvent}（満席→空きへ転じた瞬間に
 * {@code ReservationSlotService.decrementAndReopen} が発行）を購読する。</p>
 *
 * <p><b>トランザクション設計:</b> 空き復帰は確定 TX（キャンセル）の副作用であるため
 * {@code @TransactionalEventListener(AFTER_COMMIT)} で購読し、{@code @Async("event-pool")} で
 * キャンセル応答をブロックしない（管理者通知リスナーと同型）。通知に伴う {@code notified_at} 更新の
 * DB 書き込みは、委譲先 {@link ReservationWaitlistService#notifySlotReopened} が
 * {@code REQUIRES_NEW} で行う（AFTER_COMMIT 時点では ambient tx が無いため・
 * {@code feedback_transactional_event_listener_requires_new}）。本リスナー自身には
 * {@code @Transactional} を付けない（登録時バリデーション失敗で全 SpringBootTest 巻き添えを回避）。</p>
 *
 * <p><b>グループ一括キャンセル:</b> 復帰した N 枠それぞれに 1 イベントが発行されるため、枠単位で
 * 重複なく通知される（1 ユーザーは 1 枠に 1 WAITING のみ）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationWaitlistNotificationEventListener {

    private final ReservationWaitlistService waitlistService;

    /**
     * 枠の空き復帰イベントを受信し、当該枠の WAITING 登録者へ一斉通知する。
     *
     * @param event 空き復帰イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。空き発生時のキャンセル待ち通知。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSlotReopened(ReservationSlotReopenedEvent event) {
        try {
            waitlistService.notifySlotReopened(event.getTeamId(), event.getSlotId());
        } catch (Exception e) {
            // AFTER_COMMIT の副作用失敗。確定 TX は既にコミット済みのため波及させず、正直に記録する。
            log.error("キャンセル待ち空き通知に失敗しました: teamId={}, slotId={}",
                    event.getTeamId(), event.getSlotId(), e);
        }
    }
}
