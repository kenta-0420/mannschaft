package com.mannschaft.app.errorreport.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * エラーレポートの非同期通知を担当するコンポーネント。
 * Slack Webhook・SYSTEM_ADMIN プッシュ通知・severity昇格通知・リグレッション通知・解決通知を送信する。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ErrorReportNotifier {

    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;
    private final UserRoleRepository userRoleRepository;

    private final RestClient restClient = RestClient.create();

    @Value("${mannschaft.error-report.slack-webhook-url:}")
    private String slackWebhookUrl;

    @Value("${mannschaft.error-report.notify-threshold:HIGH}")
    private String notifyThreshold;

    /**
     * Slack Webhook でエラーレポートを通知する。
     * slackWebhookUrl が空の場合は何もしない。
     *
     * @param report エラーレポートエンティティ
     */
    @Async("event-pool")
    public void notifySlack(ErrorReportEntity report) {
        if (slackWebhookUrl == null || slackWebhookUrl.isBlank()) return;
        try {
            String text = String.format(":rotating_light: *[%s] フロントエンドエラー*\n> %s\nページ: %s\n発生回数: %d",
                    report.getSeverity(), report.getErrorMessage(),
                    report.getPageUrl(), report.getOccurrenceCount());
            String payload = objectMapper.writeValueAsString(Map.of("text", text));
            restClient.post().uri(slackWebhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("Slack通知送信失敗: errorReportId={}", report.getId(), e);
        }
    }

    /**
     * 全 SYSTEM_ADMIN にプッシュ通知を送信する。
     *
     * @param report エラーレポートエンティティ
     */
    @Async("event-pool")
    public void notifySystemAdmins(ErrorReportEntity report) {
        try {
            List<Long> adminIds = userRoleRepository.findSystemAdminUserIds();
            for (Long adminUserId : adminIds) {
                notificationService.createNotification(
                        adminUserId, "ERROR_REPORT_CRITICAL", NotificationPriority.HIGH,
                        "フロントエンドエラー（" + report.getSeverity() + "）",
                        String.format("エラー「%s」が %d 回発生しています",
                                ErrorReportService.truncate(report.getErrorMessage(), 50),
                                report.getOccurrenceCount()),
                        "ERROR_REPORT", report.getId(),
                        NotificationScopeType.SYSTEM, null,
                        "/system-admin/error-reports/" + report.getId(), null
                );
            }
        } catch (Exception e) {
            log.warn("SYSTEM_ADMINプッシュ通知送信失敗: errorReportId={}", report.getId(), e);
        }
    }

    /**
     * severity 昇格時の通知。Slack + SYSTEM_ADMIN プッシュ通知を送信する。
     *
     * @param report      エラーレポートエンティティ
     * @param oldSeverity 昇格前の severity
     * @param newSeverity 昇格後の severity
     */
    @Async("event-pool")
    public void notifyEscalation(ErrorReportEntity report, ErrorReportSeverity oldSeverity, ErrorReportSeverity newSeverity) {
        try {
            // Slack 通知
            if (slackWebhookUrl != null && !slackWebhookUrl.isBlank()) {
                String text = String.format(":chart_with_upwards_trend: *[%s→%s] フロントエンドエラー昇格*\n> %s\nページ: %s\n発生回数: %d",
                        oldSeverity, newSeverity, report.getErrorMessage(),
                        report.getPageUrl(), report.getOccurrenceCount());
                String payload = objectMapper.writeValueAsString(Map.of("text", text));
                restClient.post().uri(slackWebhookUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve().toBodilessEntity();
            }

            // SYSTEM_ADMIN プッシュ通知
            List<Long> adminIds = userRoleRepository.findSystemAdminUserIds();
            for (Long adminUserId : adminIds) {
                notificationService.createNotification(
                        adminUserId, "ERROR_REPORT_ESCALATION", NotificationPriority.HIGH,
                        String.format("エラー重要度が %s → %s に昇格しました", oldSeverity, newSeverity),
                        String.format("エラー「%s」が %d 回発生しています",
                                ErrorReportService.truncate(report.getErrorMessage(), 50),
                                report.getOccurrenceCount()),
                        "ERROR_REPORT", report.getId(),
                        NotificationScopeType.SYSTEM, null,
                        "/system-admin/error-reports/" + report.getId(), null
                );
            }
        } catch (Exception e) {
            log.warn("エスカレーション通知送信失敗: errorReportId={}", report.getId(), e);
        }
    }

    /**
     * リグレッション（再発）通知。severity に関わらず必ず Slack + SYSTEM_ADMIN プッシュ通知を送信する。
     *
     * @param report エラーレポートエンティティ
     */
    @Async("event-pool")
    public void notifyRegression(ErrorReportEntity report) {
        try {
            // Slack 通知（閾値無視で必ず送信）
            if (slackWebhookUrl != null && !slackWebhookUrl.isBlank()) {
                String text = String.format(":warning: *[再発] フロントエンドエラー*\n> %s\nページ: %s\n前回解決: %s",
                        report.getErrorMessage(), report.getPageUrl(), report.getResolvedAt());
                String payload = objectMapper.writeValueAsString(Map.of("text", text));
                restClient.post().uri(slackWebhookUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve().toBodilessEntity();
            }

            // SYSTEM_ADMIN プッシュ通知
            List<Long> adminIds = userRoleRepository.findSystemAdminUserIds();
            for (Long adminUserId : adminIds) {
                notificationService.createNotification(
                        adminUserId, "ERROR_REPORT_REGRESSION", NotificationPriority.HIGH,
                        "解決済みエラーが再発しました",
                        String.format("エラー「%s」が再発しました。前回の admin_note を確認してください。",
                                ErrorReportService.truncate(report.getErrorMessage(), 50)),
                        "ERROR_REPORT", report.getId(),
                        NotificationScopeType.SYSTEM, null,
                        "/system-admin/error-reports/" + report.getId(), null
                );
            }
        } catch (Exception e) {
            log.warn("リグレッション通知送信失敗: errorReportId={}", report.getId(), e);
        }
    }

    /**
     * エラーレポート解決時の報告者通知。user_id が非NULLのレポートに対してプッシュ通知を送信する。
     *
     * @param report エラーレポートエンティティ
     */
    @Async("event-pool")
    public void notifyResolution(ErrorReportEntity report) {
        try {
            if (report.getUserId() == null) return;
            notificationService.createNotification(
                    report.getUserId(), "ERROR_REPORT_RESOLVED", NotificationPriority.NORMAL,
                    "ご報告いただいた不具合が解決しました",
                    String.format("エラー「%s」への対応が完了しました。ご報告ありがとうございました。",
                            ErrorReportService.truncate(report.getErrorMessage(), 50)),
                    "ERROR_REPORT", report.getId(),
                    NotificationScopeType.PERSONAL, null,
                    null, null
            );
        } catch (Exception e) {
            log.warn("解決通知送信失敗: errorReportId={}", report.getId(), e);
        }
    }
}
