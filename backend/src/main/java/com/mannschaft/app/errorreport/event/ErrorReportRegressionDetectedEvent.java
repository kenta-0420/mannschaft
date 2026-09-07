package com.mannschaft.app.errorreport.event;

/**
 * 解決済みエラーの再発（リグレッション）を表す業務イベント（Issue #2990 L11）。
 *
 * <p>再発時は status / workflow_stage / assignee_id / sla_due_at の更新が業務トランザクションで
 * 行われる。是正前はその内側から {@code notifyRegression} を呼んでいたため、業務がロールバックしても
 * 「再発しました」という Slack / SYSTEM_ADMIN 通知だけが残る逆向きの不整合が通っていた。</p>
 *
 * @param reportId 再発した {@code error_reports.id}
 */
public record ErrorReportRegressionDetectedEvent(Long reportId) {
}
