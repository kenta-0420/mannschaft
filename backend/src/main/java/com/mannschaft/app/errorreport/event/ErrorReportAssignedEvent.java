package com.mannschaft.app.errorreport.event;

/**
 * エラーレポートの担当者が割り当てられたことを表す業務イベント（Issue #2990 L11）。
 *
 * <p>担当解除（assigneeId が NULL）では publish しない（是正前の
 * {@code ErrorReportTimelineService#assign} の分岐と同一）。</p>
 *
 * @param reportId   対象の {@code error_reports.id}
 * @param assigneeId 新しい担当者の {@code users.id}
 */
public record ErrorReportAssignedEvent(Long reportId, Long assigneeId) {
}
