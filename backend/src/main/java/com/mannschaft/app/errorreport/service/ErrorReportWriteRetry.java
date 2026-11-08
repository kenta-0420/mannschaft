package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.errorreport.ErrorReportErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/** 別 REQUIRED proxy から rollback 完了後に戻った試行だけを再実行する。 */
@Component
@RequiredArgsConstructor
public class ErrorReportWriteRetry {
    private final MeterRegistry meterRegistry;

    <T> T ingest(Function<ErrorReportWriteAttempt, T> action) {
        return execute(action, 5, true);
    }

    <T> T management(Function<ErrorReportWriteAttempt, T> action) {
        return execute(action, 3, false);
    }

    private <T> T execute(Function<ErrorReportWriteAttempt, T> action, int limit, boolean ingest) {
        for (int number = 1; ; number++) {
            ErrorReportWriteAttempt attempt = new ErrorReportWriteAttempt();
            try {
                return action.apply(attempt);
            } catch (RuntimeException failure) {
                if (attempt.committed() || !retryable(failure, ingest)) throw failure;
                if (number == limit) {
                    if (ingest) throw failure;
                    throw new BusinessException(ErrorReportErrorCode.ERROR_REPORT_014);
                }
                if (ingest) meterRegistry.counter("mannschaft.errorreport.ingest.retries").increment();
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(10, 41));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private boolean retryable(Throwable failure, boolean ingest) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CannotAcquireLockException || cause instanceof DeadlockLoserDataAccessException) {
                return true;
            }
            if (ingest && cause instanceof SQLException sql && sql.getErrorCode() == 1062
                    && sql.getMessage() != null
                    && sql.getMessage().matches("(?s).*['`](?:[^'`]*\\.)?uq_error_reports_hash_active['`].*")) {
                return true;
            }
        }
        return false;
    }
}
