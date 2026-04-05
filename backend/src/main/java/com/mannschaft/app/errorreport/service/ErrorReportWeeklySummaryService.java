package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.EmailService;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * エラーレポートの週次サマリーメール送信サービス。
 * 毎週月曜 AM9:00（JST）に全 SYSTEM_ADMIN へメール送信する。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErrorReportWeeklySummaryService {

    private final ErrorReportRepository errorReportRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    @Scheduled(cron = "0 0 9 * * MON", zone = "Asia/Tokyo")
    @SchedulerLock(name = "errorReportWeeklySummary", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void sendWeeklySummary() {
        try {
            LocalDateTime weekAgo = LocalDateTime.now().minusWeeks(1);

            // 先週の新規エラー件数
            long newCount = errorReportRepository.countByCreatedAtAfter(weekAgo);

            // 先週の CRITICAL/HIGH 件数
            long criticalHighCount = errorReportRepository
                    .countBySeverityInAndCreatedAtAfter(
                            List.of(ErrorReportSeverity.CRITICAL, ErrorReportSeverity.HIGH), weekAgo);

            // 現在の未対応（NEW + INVESTIGATING + REOPENED）
            long unresolvedCount = errorReportRepository.countByStatusIn(
                    List.of(ErrorReportStatus.NEW, ErrorReportStatus.INVESTIGATING, ErrorReportStatus.REOPENED));

            // 先週の再発（REOPENED）件数
            long reopenedCount = errorReportRepository.countByStatusAndUpdatedAtAfter(
                    ErrorReportStatus.REOPENED, weekAgo);

            // 送信条件: 先週の新規が0件かつ未対応が0件なら送信しない
            if (newCount == 0 && unresolvedCount == 0) {
                log.info("[ErrorReportWeeklySummary] 新規・未対応ともに0件のため送信スキップ");
                return;
            }

            // TOP5 エラー
            List<ErrorReportEntity> topErrors = errorReportRepository
                    .findTop5ByStatusInOrderByOccurrenceCountDesc(
                            List.of(ErrorReportStatus.NEW, ErrorReportStatus.INVESTIGATING, ErrorReportStatus.REOPENED));

            String htmlBody = buildSummaryHtml(weekAgo, newCount, criticalHighCount,
                    unresolvedCount, reopenedCount, topErrors);

            // SYSTEM_ADMIN のメールアドレスを取得して送信
            List<Long> adminIds = userRoleRepository.findSystemAdminUserIds();
            if (adminIds.isEmpty()) {
                log.warn("[ErrorReportWeeklySummary] SYSTEM_ADMIN ユーザーが見つかりません");
                return;
            }

            List<String> emails = userRepository.findAllById(adminIds).stream()
                    .map(u -> u.getEmail())
                    .filter(e -> e != null && !e.isBlank())
                    .toList();

            String subject = "[Mannschaft] エラーレポート週次サマリー";
            for (String email : emails) {
                emailService.sendEmail(email, subject, htmlBody);
            }

            log.info("[ErrorReportWeeklySummary] 週次サマリー送信完了: recipients={}, newCount={}, unresolvedCount={}",
                    emails.size(), newCount, unresolvedCount);
        } catch (Exception e) {
            log.error("[ErrorReportWeeklySummary] 週次サマリー送信失敗", e);
        }
    }

    private String buildSummaryHtml(LocalDateTime weekAgo, long newCount, long criticalHighCount,
                                     long unresolvedCount, long reopenedCount,
                                     List<ErrorReportEntity> topErrors) {
        String periodStr = weekAgo.format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))
                + " ～ " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"));

        StringBuilder top5Html = new StringBuilder();
        if (!topErrors.isEmpty()) {
            top5Html.append("<table border='1' cellpadding='6' cellspacing='0' style='border-collapse:collapse;'>");
            top5Html.append("<tr><th>#</th><th>エラーメッセージ</th><th>ページ</th><th>発生回数</th><th>影響ユーザー数</th></tr>");
            int rank = 1;
            for (ErrorReportEntity e : topErrors) {
                top5Html.append(String.format(
                        "<tr><td>%d</td><td>%s</td><td>%s</td><td>%d</td><td>%d</td></tr>",
                        rank++,
                        escapeHtml(ErrorReportService.truncate(e.getErrorMessage(), 80)),
                        escapeHtml(e.getPageUrl()),
                        e.getOccurrenceCount(),
                        e.getAffectedUserCount()));
            }
            top5Html.append("</table>");
        } else {
            top5Html.append("<p>該当なし</p>");
        }

        return String.format("""
                <html><body>
                <h2>エラーレポート週次サマリー</h2>
                <p>集計期間: %s</p>
                <table border='1' cellpadding='6' cellspacing='0' style='border-collapse:collapse;'>
                <tr><th>項目</th><th>件数</th></tr>
                <tr><td>先週の新規エラー</td><td><strong>%d</strong></td></tr>
                <tr><td>先週の CRITICAL / HIGH</td><td><strong>%d</strong></td></tr>
                <tr><td>現在の未対応（NEW + INVESTIGATING + REOPENED）</td><td><strong>%d</strong></td></tr>
                <tr><td>先週の再発（REOPENED）</td><td><strong>%d</strong></td></tr>
                </table>
                <h3>TOP5 エラー</h3>
                %s
                <p><a href="/system-admin/error-reports">管理画面で確認する</a></p>
                </body></html>
                """,
                periodStr, newCount, criticalHighCount, unresolvedCount, reopenedCount,
                top5Html.toString());
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
