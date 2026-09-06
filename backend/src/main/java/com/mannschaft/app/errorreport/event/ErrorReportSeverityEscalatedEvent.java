package com.mannschaft.app.errorreport.event;

import com.mannschaft.app.errorreport.ErrorReportSeverity;

/**
 * エラーレポートの重要度が昇格したことを表す業務イベント（Issue #2990 L11）。
 *
 * <p>昇格前後の severity は<b>業務トランザクションの内側でしか観測できない差分</b>であるため、
 * 例外的にイベントへ載せる（描画済みの文字列ではなく enum 値そのもの）。
 * 本文・受信者は配送側が {@code reportId} から読み直す。</p>
 *
 * @param reportId    対象の {@code error_reports.id}
 * @param oldSeverity 昇格前の重要度
 * @param newSeverity 昇格後の重要度
 */
public record ErrorReportSeverityEscalatedEvent(Long reportId,
                                                ErrorReportSeverity oldSeverity,
                                                ErrorReportSeverity newSeverity) {
}
