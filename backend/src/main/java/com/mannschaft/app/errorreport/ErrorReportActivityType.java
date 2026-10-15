package com.mannschaft.app.errorreport;

/**
 * F12.5 Phase 2 — エラーレポートに対する操作履歴の種別。
 * error_report_activities.activity_type にマップされる。
 */
public enum ErrorReportActivityType {
    STATUS_CHANGED,
    WORKFLOW_CHANGED,
    ASSIGNEE_CHANGED,
    SEVERITY_CHANGED,
    COMMENT_ADDED,
    AI_ANALYZED,
    GITHUB_ISSUE_CREATED,
    REOPENED,
    RESOLVED,
    /** F10.6 Phase 10-γ-① — インフラコンポーネントの Health DOWN→UP 復旧を記録する。 */
    HEALTH_RECOVERED
}
