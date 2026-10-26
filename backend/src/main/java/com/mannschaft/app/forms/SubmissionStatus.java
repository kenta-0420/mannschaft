package com.mannschaft.app.forms;

/**
 * フォーム提出のステータス。提出のライフサイクル状態を表す。
 */
public enum SubmissionStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    REJECTED,
    RETURNED
}
