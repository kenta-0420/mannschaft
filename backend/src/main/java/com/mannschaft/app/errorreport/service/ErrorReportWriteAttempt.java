package com.mannschaft.app.errorreport.service;

import org.springframework.core.Ordered;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 一試行の commit 完了を、業務 callback より先に記録する。 */
final class ErrorReportWriteAttempt implements TransactionSynchronization {
    private boolean committed;

    void begin() {
        TransactionSynchronizationManager.registerSynchronization(this);
    }

    boolean committed() { return committed; }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override
    public void afterCommit() { committed = true; }

    @Override
    public void afterCompletion(int status) {
        if (status == STATUS_COMMITTED) committed = true;
    }
}
