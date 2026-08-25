package com.mannschaft.app.safetycheck.event;

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
 * 安否確認リマインドの通知配送リスナー（Issue #2834 / CMP-056 第1群ロットA）。
 *
 * <p>{@code SafetyCheckService#sendReminder} の業務トランザクション（{@code last_reminder_at} の更新）が
 * commit された後（{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で発火する。単一受信者のため
 * 型確立PR #2910 の {@code ContactRequestNotificationListener} と同型（受信者ループを持たない側の金型）。</p>
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code sendReminder} の {@code @Transactional} 内で {@code notificationHelper.notify} を
 * 呼び、{@code try/catch} で握って継続していた。{@code createNotification} は既定の {@code REQUIRED}
 * 伝播で業務トランザクションに参加するため、通知側の DB 例外は rollback-only を残し、catch しても
 * <b>リマインド記録ごと巻き戻っていた</b>（コード内に「根治は Issue #2834 / CMP-056 の範囲」と
 * 自認するコメントが残っていた箇所）。</p>
 *
 * <h2>境界: 例外・deny のログはこの非TXリスナーで書く</h2>
 * <p>組み立て（{@code getLocale} / {@code getMessage}）も配送（{@code sendOne}）も {@code try/catch} で
 * 囲むのは本リスナー（トランザクション境界の<b>外</b>）であり、Runner 内部（{@code REQUIRES_NEW}
 * トランザクションの<b>内側</b>）では catch しない（rollback-only なら監査記録も一緒に消えるため）。</p>
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
                                + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}",
                        request.recipientUserId(), request.notificationType(),
                        request.sourceType(), request.sourceId());
            }
        } catch (Exception e) {
            log.error("安否確認リマインド通知の配送に失敗しました: "
                            + "safetyCheckId={}, recipientUserId={}, scopeType={}, scopeId={}",
                    event.safetyCheckId(), event.recipientUserId(), event.scopeType(), event.scopeId(), e);
        }
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(SafetyCheckReminderNotificationEvent event) {
        Locale locale = Locale.forLanguageTag(userLocaleCache.getLocale(event.recipientUserId()));
        return new NotificationDeliveryRequest(
                event.recipientUserId(),
                "SAFETY_CHECK_REMINDER",
                NotificationPriority.URGENT,
                messageSource.getMessage(
                        "notification.safetycheck.reminder.title", null, "安否確認リマインド", locale),
                messageSource.getMessage(
                        "notification.safetycheck.reminder.body", null,
                        "安否確認に未回答です。至急回答をお願いします。", locale),
                "SAFETY_CHECK",
                event.safetyCheckId(),
                NotificationScopeType.valueOf(event.scopeType()),
                event.scopeId(),
                "/safety-checks/" + event.safetyCheckId(),
                event.recipientUserId());
    }
}
