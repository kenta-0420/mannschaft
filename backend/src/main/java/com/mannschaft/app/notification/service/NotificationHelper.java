package com.mannschaft.app.notification.service;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通知発火ヘルパー。各モジュールから通知を簡便に作成・配信するためのファサード。
 *
 * <p>使用例:</p>
 * <pre>
 * notificationHelper.notify(userId, "SCHEDULE_REMINDER", "リマインド", "出欠未回答です",
 *         "SCHEDULE", scheduleId, NotificationScopeType.TEAM, teamId,
 *         "/schedules/" + scheduleId, actorId);
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationHelper {

    private final NotificationService notificationService;
    private final NotificationDispatchService dispatchService;

    /**
     * 単一ユーザーに通知を作成・配信する。
     */
    public void notify(Long userId, String notificationType, String title, String body,
                       String sourceType, Long sourceId,
                       NotificationScopeType scopeType, Long scopeId,
                       String actionUrl, Long actorId) {
        NotificationEntity notification = notificationService.createNotification(
                userId, notificationType, NotificationPriority.NORMAL,
                title, body, sourceType, sourceId, scopeType, scopeId, actionUrl, actorId);
        dispatchService.dispatch(notification);
    }

    /**
     * 単一ユーザーに優先度指定で通知を作成・配信する。
     */
    public void notify(Long userId, String notificationType, NotificationPriority priority,
                       String title, String body,
                       String sourceType, Long sourceId,
                       NotificationScopeType scopeType, Long scopeId,
                       String actionUrl, Long actorId) {
        NotificationEntity notification = notificationService.createNotification(
                userId, notificationType, priority,
                title, body, sourceType, sourceId, scopeType, scopeId, actionUrl, actorId);
        dispatchService.dispatch(notification);
    }

    /**
     * 複数ユーザーに一括通知を作成・配信する。
     */
    public void notifyAll(List<Long> userIds, String notificationType, String title, String body,
                          String sourceType, Long sourceId,
                          NotificationScopeType scopeType, Long scopeId,
                          String actionUrl, Long actorId) {
        for (Long userId : userIds) {
            try {
                notify(userId, notificationType, title, body,
                        sourceType, sourceId, scopeType, scopeId, actionUrl, actorId);
            } catch (Exception e) {
                log.warn("通知送信失敗（継続）: userId={}, type={}, error={}",
                        userId, notificationType, e.getMessage());
            }
        }
        log.info("一括通知送信: type={}, userCount={}", notificationType, userIds.size());
    }

    /**
     * 複数ユーザーに優先度指定で一括通知を作成・配信する。
     */
    public void notifyAll(List<Long> userIds, String notificationType, NotificationPriority priority,
                          String title, String body,
                          String sourceType, Long sourceId,
                          NotificationScopeType scopeType, Long scopeId,
                          String actionUrl, Long actorId) {
        for (Long userId : userIds) {
            try {
                notify(userId, notificationType, priority, title, body,
                        sourceType, sourceId, scopeType, scopeId, actionUrl, actorId);
            } catch (Exception e) {
                log.warn("通知送信失敗（継続）: userId={}, type={}, error={}",
                        userId, notificationType, e.getMessage());
            }
        }
        log.info("一括通知送信: type={}, priority={}, userCount={}", notificationType, priority, userIds.size());
    }
}
