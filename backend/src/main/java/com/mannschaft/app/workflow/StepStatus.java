package com.mannschaft.app.workflow;

/**
 * ワークフローステップステータス。各承認ステップの進捗状態を表す。
 */
public enum StepStatus {
    WAITING,
    IN_PROGRESS,
    APPROVED,
    REJECTED,
    SKIPPED
}
