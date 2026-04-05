package com.mannschaft.app.workflow;

/**
 * ワークフロー申請ステータス。申請のライフサイクル状態を表す。
 */
public enum WorkflowStatus {
    DRAFT,
    PENDING,
    IN_PROGRESS,
    APPROVED,
    REJECTED,
    CANCELLED,
    WITHDRAWN
}
