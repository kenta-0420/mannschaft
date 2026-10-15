package com.mannschaft.app.workflow;

/**
 * 承認者の判断。個々の承認者が下した決定を表す。
 */
public enum ApproverDecision {
    PENDING,
    APPROVED,
    REJECTED,
    DELEGATED
}
