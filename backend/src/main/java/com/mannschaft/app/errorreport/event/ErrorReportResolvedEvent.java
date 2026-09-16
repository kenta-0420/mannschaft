package com.mannschaft.app.errorreport.event;

/**
 * エラーレポートが RESOLVED になったことを表す業務イベント（Issue #2990 L11）。
 *
 * <p>報告者（{@code error_reports.user_id}）への解決通知の起点。
 * user_id が NULL のときは配送側が何もしない（判定は配送側がコミット済みの行を読み直して行う）。</p>
 *
 * @param reportId 解決された {@code error_reports.id}
 */
public record ErrorReportResolvedEvent(Long reportId) {
}
