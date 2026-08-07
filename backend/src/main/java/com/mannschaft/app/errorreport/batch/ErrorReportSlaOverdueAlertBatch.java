package com.mannschaft.app.errorreport.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import com.mannschaft.app.errorreport.service.ErrorReportService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * F10.6 Phase 10-δ — SLA期限超過アラートバッチ。
 *
 * <p>30分毎に未対応かつ sla_due_at を過ぎたレポートを検索し、
 * 担当者（assignee_id 有）には個別通知、担当者なしには全 SYSTEM_ADMIN へ通知する。
 * Valkey で「通知済み」フラグを保持し、同一レポートへの重複通知を抑制する。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ErrorReportSlaOverdueAlertBatch {

    private static final String NOTIFIED_KEY_PREFIX = "error-report:sla-overdue-notified:";
    private static final Duration NOTIFIED_TTL = Duration.ofDays(7);

    private final ErrorReportRepository errorReportRepository;
    private final UserRoleRepository userRoleRepository;
    private final NotificationHelper notificationHelper;
    private final StringRedisTemplate redisTemplate;

    private static final List<ErrorReportStatus> ACTIVE_STATUSES = List.of(
            ErrorReportStatus.NEW,
            ErrorReportStatus.INVESTIGATING,
            ErrorReportStatus.REOPENED);

    @BatchEndpoint(
            name = "errorreport-sla-overdue-alert",
            description = "SLA期限超過レポートへの通知（30分毎）")
    @Scheduled(cron = "0 0/30 * * * *", zone = "Asia/Tokyo")
    @SchedulerLock(
            name = "errorReportSlaOverdueAlert",
            lockAtMostFor = "PT60M",
            lockAtLeastFor = "PT1M")
    public void run() {
        try {
            List<ErrorReportEntity> overdueReports =
                    errorReportRepository.findOverdueReports(LocalDateTime.now(), ACTIVE_STATUSES);

            if (overdueReports.isEmpty()) return;

            List<Long> adminIds = userRoleRepository.findSystemAdminUserIds();

            for (ErrorReportEntity report : overdueReports) {
                String notifiedKey = NOTIFIED_KEY_PREFIX + report.getId();
                if (Boolean.TRUE.equals(redisTemplate.hasKey(notifiedKey))) {
                    continue;
                }

                String title = String.format("[SLA超過] %s エラーが期限切れです",
                        report.getSeverity().name());
                String body = ErrorReportService.truncate(report.getErrorMessage(), 80);

                if (report.getAssigneeId() != null) {
                    notificationHelper.notify(
                            report.getAssigneeId(),
                            "ERROR_REPORT_SLA_OVERDUE",
                            NotificationPriority.HIGH,
                            title, body,
                            "ERROR_REPORT", report.getId(),
                            NotificationScopeType.SYSTEM, null,
                            "/system-admin/error-reports/" + report.getId(), null);
                } else {
                    notificationHelper.notifyAll(
                            adminIds,
                            "ERROR_REPORT_SLA_OVERDUE",
                            NotificationPriority.HIGH,
                            title, body,
                            "ERROR_REPORT", report.getId(),
                            NotificationScopeType.SYSTEM, null,
                            "/system-admin/error-reports/" + report.getId(), null);
                }

                redisTemplate.opsForValue().set(notifiedKey, "1", NOTIFIED_TTL);
                log.info("[SlaOverdueAlert] 通知送信: reportId={}, severity={}",
                        report.getId(), report.getSeverity());
            }
        } catch (Exception e) {
            log.error("[SlaOverdueAlert] SLA超過アラート送信失敗", e);
        }
    }
}
