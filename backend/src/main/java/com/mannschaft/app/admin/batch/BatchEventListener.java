package com.mannschaft.app.admin.batch;

import com.mannschaft.app.admin.batch.event.BatchCompletedEvent;
import com.mannschaft.app.admin.batch.event.BatchFailedEvent;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.service.ErrorReportAsyncExecutor;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * F10.X 第一陣 — バッチ実行イベントの受信側。SYSTEM_ADMIN 通知と F12.5 起票を行う。
 *
 * <p>{@link BatchCompletedEvent} は SYSTEM_ADMIN への通知タブ配信のみ（priority=LOW）。
 * {@link BatchFailedEvent} は F12.5 への error_reports 起票（severity=HIGH）に加えて
 * SYSTEM_ADMIN への通知タブ配信（priority=HIGH）を行う。</p>
 *
 * <p>すべて {@code @Async("event-pool")} で非同期化する。AsyncConfig の MDC TaskDecorator により
 * requestId 等のコンテキストは引き継がれる。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchEventListener {

    /** バッチ完了通知の type 文字列（既存の汎用 type を流用、systemAdmin 向け固定）。 */
    static final String NOTIFICATION_TYPE_BATCH_COMPLETED = "BATCH_COMPLETED";
    /** バッチ失敗通知の type 文字列。 */
    static final String NOTIFICATION_TYPE_BATCH_FAILED = "BATCH_FAILED";

    private final NotificationService notificationService;
    private final UserRoleRepository userRoleRepository;
    private final ErrorReportAsyncExecutor errorReportAsyncExecutor;
    private final MessageSource messageSource;
    private final UserLocaleCache userLocaleCache;

    @Async("event-pool")
    @EventListener
    public void onCompleted(BatchCompletedEvent event) {
        try {
            List<Long> adminIds = userRoleRepository.findSystemAdminUserIds();
            int processedCount = event.log() != null && event.log().getProcessedCount() != null
                    ? event.log().getProcessedCount() : 0;
            Long sourceId = event.log() != null ? event.log().getId() : null;
            // Issue #2715 CMP-055 ロットC-6: 受信者ごとに locale が異なるため、ループの外で一括解決する（N+1 防止）。
            Map<Long, String> locales = userLocaleCache.getLocales(adminIds);
            for (Long adminUserId : adminIds) {
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(adminUserId, "ja"));
                String title = messageSource.getMessage(
                        "notification.admin.batchCompleted.title",
                        new Object[]{event.name()}, "バッチ完了: " + event.name(), locale);
                String body = messageSource.getMessage(
                        "notification.admin.batchCompleted.body",
                        new Object[]{processedCount}, "処理件数: " + processedCount + " 件", locale);
                notificationService.createNotification(
                        adminUserId,
                        NOTIFICATION_TYPE_BATCH_COMPLETED,
                        NotificationPriority.LOW,
                        title,
                        body,
                        "BATCH_JOB_LOG",
                        sourceId,
                        NotificationScopeType.SYSTEM,
                        null,
                        "/system-admin/batch-jobs" + (sourceId != null ? "/" + sourceId : ""),
                        null);
            }
        } catch (Exception ex) {
            log.warn("BatchCompletedEvent ハンドリング失敗: name={}", event.name(), ex);
        }
    }

    @Async("event-pool")
    @EventListener
    public void onFailed(BatchFailedEvent event) {
        // F12.5 起票（severity=HIGH 固定。バッチ失敗は本番では必ず HIGH 以上で扱う）
        try {
            errorReportAsyncExecutor.recordBackendException(
                    event.cause(),
                    "batch://" + event.name(),
                    "system-batch",
                    null,
                    null,
                    ErrorReportSeverity.HIGH);
        } catch (Exception ex) {
            log.warn("バッチ失敗の F12.5 起票に失敗: name={}", event.name(), ex);
        }

        // SYSTEM_ADMIN への通知配信
        try {
            List<Long> adminIds = userRoleRepository.findSystemAdminUserIds();
            // causeMessage は例外メッセージそのもの（動的な技術的文字列）であり、固定日本語リテラルではないため
            // i18n 対象外（AC-1 は notify/createNotification へ直接渡る「日本語リテラル」を対象とする）。
            String causeMessage = event.cause() != null && event.cause().getMessage() != null
                    ? event.cause().getMessage()
                    : event.cause() != null ? event.cause().getClass().getSimpleName() : "(no cause)";
            String truncatedCause = truncate(causeMessage, 200);
            Long sourceId = event.log() != null ? event.log().getId() : null;
            // Issue #2715 CMP-055 ロットC-6: 受信者ごとに locale が異なるため、ループの外で一括解決する（N+1 防止）。
            Map<Long, String> locales = userLocaleCache.getLocales(adminIds);
            for (Long adminUserId : adminIds) {
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(adminUserId, "ja"));
                String title = messageSource.getMessage(
                        "notification.admin.batchFailed.title",
                        new Object[]{event.name()}, "バッチ失敗: " + event.name(), locale);
                String body = messageSource.getMessage(
                        "notification.admin.batchFailed.body",
                        new Object[]{truncatedCause}, truncatedCause, locale);
                notificationService.createNotification(
                        adminUserId,
                        NOTIFICATION_TYPE_BATCH_FAILED,
                        NotificationPriority.HIGH,
                        title,
                        body,
                        "BATCH_JOB_LOG",
                        sourceId,
                        NotificationScopeType.SYSTEM,
                        null,
                        "/system-admin/batch-jobs" + (sourceId != null ? "/" + sourceId : ""),
                        null);
            }
        } catch (Exception ex) {
            log.warn("BatchFailedEvent 通知配信失敗: name={}", event.name(), ex);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
