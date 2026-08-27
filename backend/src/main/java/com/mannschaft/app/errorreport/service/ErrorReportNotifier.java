package com.mannschaft.app.errorreport.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mannschaft.app.common.i18n.UserLocaleCache;
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
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
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
    /** Issue #2715 CMP-055 ロットC-1: 通知本文の受信者 locale 解決（D-5: auth の UserRepository を直接呼ばない）。 */
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

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
            // Issue #2715 ロットC-1: 受信者ごとの locale を一括解決（N+1 防止）。
            Map<Long, String> locales = userLocaleCache.getLocales(adminIds);
            String truncatedMessage = ErrorReportService.truncate(report.getErrorMessage(), 50);
            for (Long adminUserId : adminIds) {
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(adminUserId, "ja"));
                String title = messageSource.getMessage(
                        "notification.errorreport.critical.title",
                        new Object[]{report.getSeverity()},
                        "フロントエンドエラー（" + report.getSeverity() + "）", locale);
                String body = messageSource.getMessage(
                        "notification.errorreport.critical.body",
                        new Object[]{truncatedMessage, report.getOccurrenceCount()},
                        String.format("エラー「%s」が %d 回発生しています", truncatedMessage, report.getOccurrenceCount()),
                        locale);
                notificationService.createNotification(
                        adminUserId, "ERROR_REPORT_CRITICAL", NotificationPriority.HIGH,
                        title, body,
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
            // Issue #2715 ロットC-1: 受信者ごとの locale を一括解決（N+1 防止）。
            Map<Long, String> locales = userLocaleCache.getLocales(adminIds);
            String truncatedMessage = ErrorReportService.truncate(report.getErrorMessage(), 50);
            for (Long adminUserId : adminIds) {
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(adminUserId, "ja"));
                String title = messageSource.getMessage(
                        "notification.errorreport.escalation.title",
                        new Object[]{oldSeverity, newSeverity},
                        String.format("エラー重要度が %s → %s に昇格しました", oldSeverity, newSeverity), locale);
                String body = messageSource.getMessage(
                        "notification.errorreport.escalation.body",
                        new Object[]{truncatedMessage, report.getOccurrenceCount()},
                        String.format("エラー「%s」が %d 回発生しています", truncatedMessage, report.getOccurrenceCount()),
                        locale);
                notificationService.createNotification(
                        adminUserId, "ERROR_REPORT_ESCALATION", NotificationPriority.HIGH,
                        title, body,
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
            // Issue #2715 ロットC-1: 受信者ごとの locale を一括解決（N+1 防止）。
            Map<Long, String> locales = userLocaleCache.getLocales(adminIds);
            String truncatedMessage = ErrorReportService.truncate(report.getErrorMessage(), 50);
            for (Long adminUserId : adminIds) {
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(adminUserId, "ja"));
                String title = messageSource.getMessage(
                        "notification.errorreport.regression.title", null,
                        "解決済みエラーが再発しました", locale);
                String body = messageSource.getMessage(
                        "notification.errorreport.regression.body",
                        new Object[]{truncatedMessage},
                        String.format("エラー「%s」が再発しました。前回の admin_note を確認してください。", truncatedMessage),
                        locale);
                notificationService.createNotification(
                        adminUserId, "ERROR_REPORT_REGRESSION", NotificationPriority.HIGH,
                        title, body,
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
            Locale locale = Locale.forLanguageTag(userLocaleCache.getLocale(newAssigneeId));
            String title = messageSource.getMessage(
                    "notification.errorreport.assigned.title", null,
                    "エラーレポートが割り当てられました", locale);
            notificationService.createNotification(
                    newAssigneeId, "ERROR_REPORT_ASSIGNED", NotificationPriority.NORMAL,
                    title,
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
            // Issue #2715 ロットC-1: 受信者ごとの locale を一括解決（N+1 防止）。
            Map<Long, String> locales = userLocaleCache.getLocales(adminIds);
            for (Long adminUserId : adminIds) {
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(adminUserId, "ja"));
                String title = messageSource.getMessage(
                        "notification.errorreport.aiAnalyzed.title", null,
                        "エラーレポートの AI 分析が完了しました", locale);
                notificationService.createNotification(
                        adminUserId, "ERROR_REPORT_AI_ANALYZED", NotificationPriority.NORMAL,
                        title,
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
        sendBudgetSlack(":warning:", "notification.errorreport.aiBudgetWarning.title", "AI 月次予算 80% 到達",
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
        sendBudgetSlack(":no_entry:", "notification.errorreport.aiBudgetExceeded.title",
                "AI 月次予算上限到達（以降の AI 分析は停止）", budgetJpy, currentJpy);
    }

    /**
     * 予算アラート用 Slack + SYSTEM_ADMIN 通知の共通処理。
     *
     * @param titleKey     受信者 locale で解決するタイトルの i18n キー
     * @param defaultTitle {@code titleKey} 未登録時のフォールバック（日本語・Slack 本文にも使用）
     */
    private void sendBudgetSlack(String emoji, String titleKey, String defaultTitle, int budgetJpy, long currentJpy) {
        try {
            if (slackWebhookUrl != null && !slackWebhookUrl.isBlank()) {
                String text = String.format("%s *%s*\n月次予算: ¥%d / 累計: ¥%d",
                        emoji, defaultTitle, budgetJpy, currentJpy);
                String payload = objectMapper.writeValueAsString(Map.of("text", text));
                restClient.post().uri(slackWebhookUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve().toBodilessEntity();
            }
            List<Long> adminIds = userRoleRepository.findSystemAdminUserIds();
            // Issue #2715 ロットC-1: 受信者ごとの locale を一括解決（N+1 防止）。
            Map<Long, String> locales = userLocaleCache.getLocales(adminIds);
            for (Long adminUserId : adminIds) {
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(adminUserId, "ja"));
                String title = messageSource.getMessage(titleKey, null, defaultTitle, locale);
                String body = messageSource.getMessage(
                        "notification.errorreport.aiBudget.body",
                        new Object[]{budgetJpy, currentJpy},
                        String.format("月次予算 ¥%d / 累計 ¥%d", budgetJpy, currentJpy),
                        locale);
                notificationService.createNotification(
                        adminUserId, "ERROR_REPORT_AI_BUDGET", NotificationPriority.HIGH,
                        title, body,
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
            // Issue #2715 ロットC-1: 受信者ごとの locale を一括解決（N+1 防止）。
            Map<Long, String> locales = userLocaleCache.getLocales(adminIds);
            for (Long adminUserId : adminIds) {
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(adminUserId, "ja"));
                String title = messageSource.getMessage(
                        "notification.errorreport.aiHealthDegraded.title", null,
                        "AI 分析サービスの異常を検知しました", locale);
                String body = messageSource.getMessage(
                        "notification.errorreport.aiHealthDegraded.body",
                        new Object[]{failureCount},
                        String.format("直近 24 時間で AI 分析が %d 件失敗しています", failureCount),
                        locale);
                notificationService.createNotification(
                        adminUserId, "ERROR_REPORT_AI_HEALTH", NotificationPriority.HIGH,
                        title, body,
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
     * F10.6 §5.6-③ — 集約サマリ通知。
     *
     * <p>{@link com.mannschaft.app.errorreport.batch.ErrorAggregationFlushBatch} が 5 分毎に呼び、
     * 「直近 5 分で N 件発生」と 1 通の Slack メッセージにまとめて送信する。
     * 本メソッドはクールダウンチェックをしない（バッチ自身が 5 分間隔のため重複は発生しない）。</p>
     *
     * <p><b>複数 Pod で同一時間窓に複数通が届くことについて</b>:
     * 呼び出し元は Pod ローカルのメモリバッファをドレインするため分散排他を掛けられず
     * （掛けると敗者 Pod のエラーが取りこぼされる）、Pod 数だけ本通知が飛ぶ。
     * ただし各通の内容は<b>互いに素なスライス</b>であり同じエラーを二重に数えてはいない。
     * 「重複通知に見える」ことこそが運用上の害であるため、
     * 送信元インスタンスを本文に明記して<b>スライスであることを読み取れる形</b>にする。
     * 送信そのものを 1 通へ束ねるには全 Pod のドレインを待ち合わせる共有ストアが必要になり、
     * 待ち合わせ中に Pod が落ちるとそのスライスを失う（＝取りこぼしの再導入）ため採用しない。</p>
     *
     * <p>渡された Map が空の場合は何もしない（バッチ側でフィルタ済み想定だが二重防衛）。
     * Slack Webhook URL が空の場合も送信しない。</p>
     *
     * @param entries error_hash → AggregatedEntry のマップ（occurrenceCount &gt;= 2 の entry のみ想定）
     */
    @Async("event-pool")
    public void notifyAggregatedSummary(Map<String, com.mannschaft.app.errorreport.service.ErrorReportAggregator.AggregatedEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        if (slackWebhookUrl == null || slackWebhookUrl.isBlank()) return;
        try {
            int errorTypeCount = entries.size();
            long totalOccurrences = entries.values().stream()
                    .mapToLong(com.mannschaft.app.errorreport.service.ErrorReportAggregator.AggregatedEntry::occurrenceCount)
                    .sum();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(":bar_chart: *直近5分のエラー集約: %d種のエラーが計%d回発生* (instance: %s)%n",
                    errorTypeCount, totalOccurrences, resolveInstanceLabel()));
            // 詳細は最大 10 件まで（Slack メッセージ過大化防止）
            int shown = 0;
            for (Map.Entry<String, com.mannschaft.app.errorreport.service.ErrorReportAggregator.AggregatedEntry> e : entries.entrySet()) {
                if (shown >= 10) {
                    sb.append(String.format("  …他 %d 種省略%n", entries.size() - shown));
                    break;
                }
                com.mannschaft.app.errorreport.service.ErrorReportAggregator.AggregatedEntry entry = e.getValue();
                String hashShort = entry.errorHash() != null && entry.errorHash().length() >= 8
                        ? entry.errorHash().substring(0, 8) : entry.errorHash();
                String severityLabel = entry.severity() != null ? entry.severity().name() : "-";
                String message = ErrorReportService.truncate(entry.message(), 120);
                sb.append(String.format("  - [%s] hash:%s (%d回): %s%n",
                        severityLabel, hashShort, entry.occurrenceCount(),
                        message != null ? message : "(no message)"));
                shown++;
            }
            String payload = objectMapper.writeValueAsString(Map.of("text", sb.toString()));
            restClient.post().uri(slackWebhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("集約サマリ通知送信失敗: entryCount={}", entries.size(), e);
        }
    }

    /**
     * 集約サマリの送信元インスタンスを表すラベルを返す。
     *
     * <p>複数 Pod から同一時間窓のサマリが届いたとき、それが「同じ内容の重複」ではなく
     * 「別インスタンスのスライス」であることを受け手が判別できるようにするための識別子。
     * Kubernetes では Pod 名が {@code HOSTNAME} に入る。取得できない環境では
     * ホスト名にフォールバックし、それも失敗したら {@code unknown} とする
     * （ラベル取得の失敗でサマリ送信そのものを落としてはならない）。</p>
     *
     * @return インスタンス識別ラベル
     */
    private String resolveInstanceLabel() {
        String podName = System.getenv("HOSTNAME");
        if (podName != null && !podName.isBlank()) {
            return podName;
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            log.debug("インスタンス識別ラベルの解決に失敗したため unknown を用いる", e);
            return "unknown";
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
            Locale locale = Locale.forLanguageTag(userLocaleCache.getLocale(report.getUserId()));
            String truncatedMessage = ErrorReportService.truncate(report.getErrorMessage(), 50);
            String title = messageSource.getMessage(
                    "notification.errorreport.resolved.title", null,
                    "ご報告いただいた不具合が解決しました", locale);
            String body = messageSource.getMessage(
                    "notification.errorreport.resolved.body",
                    new Object[]{truncatedMessage},
                    String.format("エラー「%s」への対応が完了しました。ご報告ありがとうございました。", truncatedMessage),
                    locale);
            notificationService.createNotification(
                    report.getUserId(), "ERROR_REPORT_RESOLVED", NotificationPriority.NORMAL,
                    title, body,
                    "ERROR_REPORT", report.getId(),
                    NotificationScopeType.PERSONAL, null,
                    null, null
            );
        } catch (Exception e) {
            log.warn("解決通知送信失敗: errorReportId={}", report.getId(), e);
        }
    }
}
