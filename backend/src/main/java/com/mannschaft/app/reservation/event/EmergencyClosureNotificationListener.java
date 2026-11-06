package com.mannschaft.app.reservation.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.reservation.entity.EmergencyClosureEntity;
import com.mannschaft.app.reservation.repository.EmergencyClosureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 臨時休業の一括通知の配送リスナー（Issue #2997 / CMP-260827-1152 G3）。
 *
 * <p>{@code EmergencyClosureService#sendClosure} の業務TX（休業履歴・予約キャンセル・確認追跡行）が commit された後
 * （{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で発火する。受信者ごとに
 * {@link NotificationDeliveryRunner#sendOne}（{@code REQUIRES_NEW}）で配送し、1 人の失敗を内側 try で隔離する。</p>
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code NotificationHelper#notifyAll} を {@code sendClosure} の業務TXの内側で呼んでいた。
 * 通知は既定の {@code REQUIRED} で業務TXに参加するため、通知の DB 例外が rollback-only を残し、
 * <b>休業履歴・予約キャンセル・確認追跡行までコミット時に巻き戻る</b>状態だった。</p>
 *
 * <h2>配送意味論（是正前と同一）</h2>
 * <p>通知種別 {@code EMERGENCY_CLOSURE}・優先度 URGENT・件名＝メール件名・本文＝休業理由・
 * スコープ TEAM・実行者＝休業の送信者は同一。可視性判定は {@code sendOne} の事前認可に任せる
 * （deny は例外ではなく {@code VISIBILITY_DENIED} で、その受信者ぶんは送られない）。</p>
 *
 * <h2>既知の限界</h2>
 * <p>event-pool が飽和して投入が拒否された回の通知は失われる（呼び出し元の休業登録自体はコミット済み。M9）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmergencyClosureNotificationListener {

    static final String NOTIFICATION_TYPE = "EMERGENCY_CLOSURE";
    static final String SOURCE_TYPE = "EMERGENCY_CLOSURE";

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final EmergencyClosureRepository closureRepository;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "予約は棚卸し台帳で beta=コア・gate_key=null の常時提供機能であり、"
                    + "臨時休業の周知だけを止める停止条件が存在しない。"
                    + "休業が届かないと来院事故につながるため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmergencyClosureNotification(EmergencyClosureNotificationEvent event) {
        List<Long> recipients = event.recipientUserIds();
        if (recipients.isEmpty()) {
            return;
        }

        // 文面の材料はイベントではなく元の行から読み直す（業務TXは commit 済みで休業行は生存している）。
        EmergencyClosureEntity closure;
        try {
            closure = closureRepository.findById(event.closureId()).orElse(null);
        } catch (Exception e) {
            log.error("臨時休業通知の元データ読み直しに失敗しました（配送を中止）: closureId={}", event.closureId(), e);
            return;
        }
        if (closure == null) {
            log.error("臨時休業通知の元データが見つかりません（配送を中止）: closureId={}", event.closureId());
            return;
        }

        int delivered = 0;
        int denied = 0;
        int failed = 0;
        Long firstFailedUserId = null;
        for (Long recipientUserId : recipients) {
            try {
                NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(
                        buildRequest(closure, recipientUserId));
                if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                    // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                    denied++;
                } else {
                    delivered++;
                }
            } catch (Exception e) {
                failed++;
                if (firstFailedUserId == null) {
                    firstFailedUserId = recipientUserId;
                }
                // 非同期イベント失敗の監査記録（規約上必須）。catch は TX 外なので rollback で消えない。
                log.error("臨時休業通知の配送に失敗しました: recipientUserId={}, closureId={}",
                        recipientUserId, event.closureId(), e);
            }
        }

        String summary = "臨時休業通知の一括配送結果: closureId={}, total={}, delivered={}, failed={}, denied={}, "
                + "firstFailedUserId={}";
        if (failed > 0) {
            log.error(summary, event.closureId(), recipients.size(), delivered, failed, denied, firstFailedUserId);
        } else if (denied > 0) {
            log.warn(summary, event.closureId(), recipients.size(), delivered, failed, denied, firstFailedUserId);
        } else {
            log.info("臨時休業通知送信: closureId={}, 宛先人数={}", event.closureId(), recipients.size());
        }
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(EmergencyClosureEntity closure, Long recipientUserId) {
        return new NotificationDeliveryRequest(
                recipientUserId,
                NOTIFICATION_TYPE,
                NotificationPriority.URGENT,
                closure.getSubject(),
                closure.getReason(),
                SOURCE_TYPE,
                closure.getId(),
                NotificationScopeType.TEAM,
                closure.getTeamId(),
                null,
                closure.getCreatedBy());
    }
}
