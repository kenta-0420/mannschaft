package com.mannschaft.app.errorreport.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.entity.ErrorReportAiAnalysisEntity;
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

import java.time.Duration;
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
     * F10.5 Phase 10-β / F10.6 Phase 10-β-1 — スローリクエスト通知のクールダウンキャッシュ。
     * 同一 method+path で 1 分間に 1 回だけ Slack 通知する。
     * 設計書 F10.5 §5.2.2 / F10.6 §5.6 で要求される重複アラート抑制。
     */
    private final Cache<String, Boolean> slowRequestCooldown = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(1000)
            .build();

    /**
     * F10.5 Phase 10-β / F10.6 Phase 10-β-1 — Health DOWN 通知のクールダウンキャッシュ。
     * component 単位で 5 分に 1 回だけ通知する。
     */
    private final Cache<String, Boolean> healthDownCooldown = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(100)
            .build();

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
     * F12.5 Phase 2 — エラーレポート担当者割り当て通知。
     * 割り当てられた管理者にプッシュ通知を送信する（解除時は呼ばない）。
     *
     * @param report         エラーレポートエンティティ
     * @param newAssigneeId  新しい担当者ユーザーID（NULL の場合は何もしない）
     */
    @Async("event-pool")
    public void notifyAssignment(ErrorReportEntity report, Long newAssigneeId) {
        if (newAssigneeId == null) return;
        try {
            notificationService.createNotification(
                    newAssigneeId, "ERROR_REPORT_ASSIGNED", NotificationPriority.NORMAL,
                    "エラーレポートが割り当てられました",
                    ErrorReportService.truncate(report.getErrorMessage(), 50),
                    "ERROR_REPORT", report.getId(),
                    NotificationScopeType.PERSONAL, null,
                    "/system-admin/error-reports/" + report.getId(), null
            );
        } catch (Exception e) {
            log.warn("担当者割り当て通知送信失敗: errorReportId={}, assigneeId={}",
                    report.getId(), newAssigneeId, e);
        }
    }

    /**
     * F12.5 Phase 2-C — AI 分析完了通知。
     * CRITICAL レポートのみ Slack + SYSTEM_ADMIN プッシュ通知を送信する。
     *
     * @param report   エラーレポートエンティティ
     * @param analysis 分析履歴エンティティ（SUCCESS のみ想定）
     */
    @Async("event-pool")
    public void notifyAiAnalysisCompleted(ErrorReportEntity report,
                                          ErrorReportAiAnalysisEntity analysis) {
        try {
            String summary = ErrorReportService.truncate(analysis.getEstimatedCause(), 200);
            // Slack 通知
            if (slackWebhookUrl != null && !slackWebhookUrl.isBlank()) {
                String text = String.format(
                        ":robot_face: *[AI分析] %s*\n> %s\n推定原因: %s",
                        report.getSeverity(),
                        ErrorReportService.truncate(report.getErrorMessage(), 100),
                        summary != null ? summary : "(分析結果なし)");
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
                        adminUserId, "ERROR_REPORT_AI_ANALYZED", NotificationPriority.NORMAL,
                        "エラーレポートの AI 分析が完了しました",
                        ErrorReportService.truncate(report.getErrorMessage(), 50),
                        "ERROR_REPORT", report.getId(),
                        NotificationScopeType.SYSTEM, null,
                        "/system-admin/error-reports/" + report.getId(), null
                );
            }
        } catch (Exception e) {
            log.warn("AI 分析完了通知送信失敗: errorReportId={}", report.getId(), e);
        }
    }

    /**
     * F12.5 Phase 2-C — AI 月次予算 80% 到達警告通知。
     *
     * @param budgetJpy  月次予算（円）
     * @param currentJpy 現在の累計支出（円）
     */
    @Async("event-pool")
    public void notifyBudgetWarning(int budgetJpy, long currentJpy) {
        sendBudgetSlack(":warning:", "AI 月次予算 80% 到達",
                budgetJpy, currentJpy);
    }

    /**
     * F12.5 Phase 2-C — AI 月次予算 100% 到達アラート通知。
     *
     * @param budgetJpy  月次予算（円）
     * @param currentJpy 現在の累計支出（円）
     */
    @Async("event-pool")
    public void notifyBudgetExceeded(int budgetJpy, long currentJpy) {
        sendBudgetSlack(":no_entry:", "AI 月次予算上限到達（以降の AI 分析は停止）",
                budgetJpy, currentJpy);
    }

    /**
     * 予算アラート用 Slack + SYSTEM_ADMIN 通知の共通処理。
     */
    private void sendBudgetSlack(String emoji, String title, int budgetJpy, long currentJpy) {
        try {
            if (slackWebhookUrl != null && !slackWebhookUrl.isBlank()) {
                String text = String.format("%s *%s*\n月次予算: ¥%d / 累計: ¥%d",
                        emoji, title, budgetJpy, currentJpy);
                String payload = objectMapper.writeValueAsString(Map.of("text", text));
                restClient.post().uri(slackWebhookUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve().toBodilessEntity();
            }
            List<Long> adminIds = userRoleRepository.findSystemAdminUserIds();
            for (Long adminUserId : adminIds) {
                notificationService.createNotification(
                        adminUserId, "ERROR_REPORT_AI_BUDGET", NotificationPriority.HIGH,
                        title,
                        String.format("月次予算 ¥%d / 累計 ¥%d", budgetJpy, currentJpy),
                        "ERROR_REPORT", null,
                        NotificationScopeType.SYSTEM, null,
                        "/system-admin/error-reports", null
                );
            }
        } catch (Exception e) {
            log.warn("AI 予算アラート通知送信失敗: budget={}, current={}", budgetJpy, currentJpy, e);
        }
    }

    /**
     * F12.5 Phase 2-F — AI 分析失敗連続検知通知。
     * 24h 以内に閾値以上の FAILED 分析が観測された場合に Slack + SYSTEM_ADMIN 通知を送信する。
     *
     * @param failureCount 24h 以内の FAILED 件数
     */
    @Async("event-pool")
    public void notifyAiHealthDegraded(long failureCount) {
        try {
            if (slackWebhookUrl != null && !slackWebhookUrl.isBlank()) {
                String text = String.format(
                        ":warning: *AI 分析サービス異常検知*\n直近 24 時間で %d 件の AI 分析が失敗しました。"
                                + "Claude API キーや残高、レート制限の状況を確認してください。",
                        failureCount);
                String payload = objectMapper.writeValueAsString(Map.of("text", text));
                restClient.post().uri(slackWebhookUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve().toBodilessEntity();
            }
            List<Long> adminIds = userRoleRepository.findSystemAdminUserIds();
            for (Long adminUserId : adminIds) {
                notificationService.createNotification(
                        adminUserId, "ERROR_REPORT_AI_HEALTH", NotificationPriority.HIGH,
                        "AI 分析サービスの異常を検知しました",
                        String.format("直近 24 時間で AI 分析が %d 件失敗しています", failureCount),
                        "ERROR_REPORT", null,
                        NotificationScopeType.SYSTEM, null,
                        "/system-admin/error-reports", null
                );
            }
        } catch (Exception e) {
            log.warn("AI ヘルス劣化通知送信失敗: failureCount={}", failureCount, e);
        }
    }

    /**
     * F10.5 Phase 10-β / F10.6 Phase 10-β-1 — スローリクエスト Slack 通知。
     *
     * <p>{@link com.mannschaft.app.config.RequestLoggingFilter} が ERROR_THRESHOLD_MS（10秒）
     * を超えるリクエストを検知した際に呼ばれる。同一 method+path について 1 分間に 1 回だけ
     * Slack に通知する（設計書 F10.5 §5.2.2 / F10.6 §5.6 重複抑制）。</p>
     *
     * @param method      HTTP メソッド
     * @param path        リクエスト URI
     * @param durationMs  経過ミリ秒
     * @param requestId   MDC requestId（NULL 可）
     */
    @Async("event-pool")
    public void notifySlowRequest(String method, String path, long durationMs, String requestId) {
        if (slackWebhookUrl == null || slackWebhookUrl.isBlank()) return;
        String cooldownKey = "slow-request:" + method + ":" + path;
        if (slowRequestCooldown.getIfPresent(cooldownKey) != null) {
            // 1 分以内に同じ key で通知済み → スキップ
            return;
        }
        slowRequestCooldown.put(cooldownKey, Boolean.TRUE);
        try {
            String text = String.format(
                    ":warning: *Slow Request*\n%s %s took %d ms (requestId=%s)",
                    method, path, durationMs,
                    requestId != null ? requestId : "-");
            String payload = objectMapper.writeValueAsString(Map.of("text", text));
            restClient.post().uri(slackWebhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("スローリクエスト通知送信失敗: method={}, path={}, durationMs={}",
                    method, path, durationMs, e);
        }
    }

    /**
     * F10.5 Phase 10-β / F10.6 Phase 10-β-1 — Health Indicator DOWN 通知。
     *
     * <p>Spring Boot Actuator の {@code /actuator/health} で DB / Redis 等のコンポーネントが
     * DOWN になった際に呼ばれる。同一 component について 5 分に 1 回だけ Slack に通知する。</p>
     *
     * @param component  コンポーネント名（"db" / "redis" など）
     * @param detail     詳細メッセージ（例外メッセージ等）
     */
    @Async("event-pool")
    public void notifyHealthDown(String component, String detail) {
        if (slackWebhookUrl == null || slackWebhookUrl.isBlank()) return;
        if (healthDownCooldown.getIfPresent(component) != null) {
            return;
        }
        healthDownCooldown.put(component, Boolean.TRUE);
        try {
            String text = String.format(
                    ":rotating_light: *Health DOWN*\ncomponent: `%s`\ndetail: %s",
                    component, detail != null ? detail : "(no detail)");
            String payload = objectMapper.writeValueAsString(Map.of("text", text));
            restClient.post().uri(slackWebhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("Health DOWN 通知送信失敗: component={}", component, e);
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
