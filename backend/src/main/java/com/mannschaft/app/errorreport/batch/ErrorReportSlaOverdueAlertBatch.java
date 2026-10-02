package com.mannschaft.app.errorreport.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
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
import org.springframework.context.MessageSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    /** Issue #2715 CMP-055 ロットC-1: 通知本文の受信者 locale 解決（D-5: auth の UserRepository を直接呼ばない）。 */
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    private static final List<ErrorReportStatus> ACTIVE_STATUSES = List.of(
            ErrorReportStatus.NEW,
            ErrorReportStatus.INVESTIGATING,
            ErrorReportStatus.REOPENED);

    @BatchEndpoint(
            name = "errorreport-sla-overdue-alert",
            description = "SLA期限超過レポートへの通知（30分毎）")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。エラーレポート SLA 超過のアラートであり、運用基盤に属し機能フラグを持たない。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
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
            // Issue #2715 ロットC-1: 受信者ごとの locale を一括解決（N+1 防止・report ループの外で 1 回のみ）。
            Map<Long, String> adminLocales = userLocaleCache.getLocales(adminIds);

            for (ErrorReportEntity report : overdueReports) {
                String notifiedKey = NOTIFIED_KEY_PREFIX + report.getId();
                if (Boolean.TRUE.equals(redisTemplate.hasKey(notifiedKey))) {
                    continue;
                }

                String defaultTitle = String.format("[SLA超過] %s エラーが期限切れです",
                        report.getSeverity().name());
                String body = ErrorReportService.truncate(report.getErrorMessage(), 80);

                if (report.getAssigneeId() != null) {
                    Locale locale = Locale.forLanguageTag(userLocaleCache.getLocale(report.getAssigneeId()));
                    String title = messageSource.getMessage(
                            "notification.errorreport.slaOverdue.title",
                            new Object[]{report.getSeverity().name()}, defaultTitle, locale);
                    notificationHelper.notify(
                            report.getAssigneeId(),
                            "ERROR_REPORT_SLA_OVERDUE",
                            NotificationPriority.HIGH,
                            title, body,
                            "ERROR_REPORT", report.getId(),
                            NotificationScopeType.SYSTEM, null,
                            "/system-admin/error-reports/" + report.getId(), null);
                } else {
                    for (Long adminUserId : adminIds) {
                        Locale locale = Locale.forLanguageTag(adminLocales.getOrDefault(adminUserId, "ja"));
                        String title = messageSource.getMessage(
                                "notification.errorreport.slaOverdue.title",
                                new Object[]{report.getSeverity().name()}, defaultTitle, locale);
                        notificationHelper.notify(
                                adminUserId,
                                "ERROR_REPORT_SLA_OVERDUE",
                                NotificationPriority.HIGH,
                                title, body,
                                "ERROR_REPORT", report.getId(),
                                NotificationScopeType.SYSTEM, null,
                                "/system-admin/error-reports/" + report.getId(), null);
                    }
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
