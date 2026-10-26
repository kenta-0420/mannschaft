package com.mannschaft.app.analytics;

import com.mannschaft.app.analytics.entity.AnalyticsMonthlySnapshotEntity;

/**
 * 月次KPIレポートのHTMLメール本文を生成するユーティリティ。
 */
public final class MonthlyReportHtmlBuilder {

    private MonthlyReportHtmlBuilder() {}

    public static String build(String month, AnalyticsMonthlySnapshotEntity s) {
        return """
                <html><body>
                <h2>月次KPIレポート %s</h2>
                <table border="1" cellpadding="6" cellspacing="0">
                  <tr><th>指標</th><th>値</th></tr>
                  <tr><td>MRR</td><td>%s</td></tr>
                  <tr><td>ARR</td><td>%s</td></tr>
                  <tr><td>ARPU</td><td>%s</td></tr>
                  <tr><td>LTV</td><td>%s</td></tr>
                  <tr><td>総ユーザー数</td><td>%d</td></tr>
                  <tr><td>アクティブユーザー</td><td>%d</td></tr>
                  <tr><td>有料ユーザー</td><td>%d</td></tr>
                  <tr><td>新規ユーザー</td><td>%d</td></tr>
                  <tr><td>解約ユーザー</td><td>%d</td></tr>
                  <tr><td>ユーザーチャーン率</td><td>%s%%</td></tr>
                  <tr><td>収益チャーン率</td><td>%s%%</td></tr>
                  <tr><td>純収益</td><td>%s</td></tr>
                  <tr><td>広告収益</td><td>%s</td></tr>
                </table>
                <p style="color:#666;font-size:12px;">This is an automated report from Mannschaft.</p>
                </body></html>
                """.formatted(
                month,
                s.getMrr(), s.getArr(), s.getArpu(), s.getLtv(),
                s.getTotalUsers(), s.getActiveUsers(), s.getPayingUsers(),
                s.getNewUsers(), s.getChurnedUsers(),
                s.getUserChurnRate(), s.getRevenueChurnRate(),
                s.getNetRevenue(), s.getAdRevenue()
        );
    }
}
