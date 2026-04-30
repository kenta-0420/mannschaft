package com.mannschaft.app.shift.event;

import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * シフト公開通知リスナー。
 * ShiftPublishedEvent を購読し、チーム全員に「シフトが公開されました」通知を送信する。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftPublishedNotificationListener {

    private final NotificationHelper notificationHelper;
    private final UserRoleRepository userRoleRepository;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShiftPublished(ShiftPublishedEvent event) {
        try {
            List<Long> memberIds = userRoleRepository
                    .findUserIdsByScope("TEAM", event.getTeamId());

            if (memberIds.isEmpty()) return;

            notificationHelper.notifyAll(
                    memberIds,
                    "SHIFT_PUBLISHED",
                    "シフトが公開されました",
                    "シフトスケジュールが確定・公開されました。内容を確認してください。",
                    "SHIFT_SCHEDULE", event.getScheduleId(),
                    NotificationScopeType.TEAM, event.getTeamId(),
                    "/shifts/schedules/" + event.getScheduleId(),
                    event.getTriggeredByUserId());

            log.info("シフト公開通知送信: scheduleId={}, teamId={}, 対象人数={}",
                    event.getScheduleId(), event.getTeamId(), memberIds.size());
        } catch (Exception e) {
            log.error("シフト公開通知送信失敗: scheduleId={}", event.getScheduleId(), e);
        }
    }
}
