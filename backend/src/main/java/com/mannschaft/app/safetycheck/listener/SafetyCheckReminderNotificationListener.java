package com.mannschaft.app.safetycheck.listener;

import com.mannschaft.app.safetycheck.event.SafetyCheckReminderNotificationEvent;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Locale;

/**
 * 安否確認リマインドの通知配送リスナー（Issue #2834 / CMP-056 横展開）。
 *
 * <p>{@code SafetyCheckService#sendReminder} の業務トランザクションが commit された後
 * （{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で発火する。通知の文面組み立て
 * （ロケール解決・件名/本文組み立て）も本リスナーの責務。業務行（{@code lastReminderAt} の更新）は
 * AFTER_COMMIT の時点で既にコミット済みのため、ここで参照を行っても業務トランザクションを
 * 巻き込まない。実際の生成・配信は {@link NotificationDeliveryRunner#sendOne}（{@code REQUIRES_NEW}）
 * へ委譲する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SafetyCheckReminderNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSafetyCheckReminderNotification(SafetyCheckReminderNotificationEvent event) {
        try {
            NotificationDeliveryRequest request = buildRequest(event);
            NotificationEntity created = notificationDeliveryRunner.sendOne(request);
            if (created == null) {
                // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                log.warn("安否確認リマインド通知が visibility deny によりスキップされました: "
                                + "recipientUserId={}, safetyCheckId={}",
                        request.recipientUserId(), event.safetyCheckId());
            }
        } catch (Exception e) {
            log.error("安否確認リマインド通知の配送に失敗しました: safetyCheckId={}, recipientId={}",
                    event.safetyCheckId(), event.recipientId(), e);
        }
    }

    private NotificationDeliveryRequest buildRequest(SafetyCheckReminderNotificationEvent event) {
        Locale locale = Locale.forLanguageTag(userLocaleCache.getLocale(event.recipientId()));
        return new NotificationDeliveryRequest(
                event.recipientId(),
                "SAFETY_CHECK_REMINDER",
                NotificationPriority.URGENT,
                messageSource.getMessage(
                        "notification.safetycheck.reminder.title", null, "安否確認リマインド", locale),
                messageSource.getMessage(
                        "notification.safetycheck.reminder.body", null,
                        "安否確認に未回答です。至急回答をお願いします。", locale),
                "SAFETY_CHECK",
                event.safetyCheckId(),
                NotificationScopeType.valueOf(event.scopeType().name()),
                event.scopeId(),
                "/safety-checks/" + event.safetyCheckId(),
                event.recipientId());
    }
}
