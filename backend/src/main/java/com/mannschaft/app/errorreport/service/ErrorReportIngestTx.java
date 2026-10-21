package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.SlaPolicy;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.event.ErrorReportRaisedEvent;
import com.mannschaft.app.errorreport.event.ErrorReportAiAnalysisRequestedEvent;
import com.mannschaft.app.errorreport.event.ErrorReportRegressionDetectedEvent;
import com.mannschaft.app.errorreport.event.ErrorReportSeverityEscalatedEvent;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

/** 非 IGNORED 最小 id の locking read から flush までを一試行に閉じる。 */
@Service
@RequiredArgsConstructor
public class ErrorReportIngestTx {
    enum Source { FE, BACKEND, HEALTH }

    private final ErrorReportRepository repository;
    private final ErrorReportIngestHook hook;
    private final ApplicationEventPublisher eventPublisher;
    private final ErrorReportAggregator aggregator;
    private final StringRedisTemplate redisTemplate;

    @Transactional
    ErrorReportEntity ingestOnce(ErrorReportEntity candidate, Source source,
                                  int observedAffected, ErrorReportWriteAttempt attempt) {
        attempt.begin();
        String hash = candidate.getErrorHash();
        var existing = repository.findFirstActiveForUpdate(hash);
        hook.afterRead(hash, existing.isPresent());
        hook.afterLockingRead(hash, existing.isPresent());
        ErrorReportEntity report;
        boolean created = existing.isEmpty();
        boolean reopened = false;
        ErrorReportSeverity previousSeverity = null;
        if (created) {
            if (source == Source.HEALTH) {
                var ignored = repository.findFirstByErrorHashOrderByIdDesc(hash);
                if (ignored.isPresent()) return ignored.get();
            }
            // IDENTITY INSERT で candidate 自体に id を付けず、rollback 後も新規行として再利用する。
            report = repository.save(candidate.toBuilder().build());
            hook.afterSave(hash);
        } else {
            report = existing.orElseThrow();
            previousSeverity = report.getSeverity();
            if (report.getStatus() == ErrorReportStatus.RESOLVED) {
                if (source == Source.HEALTH) return report;
                report.reopen(candidate.getOccurredAt());
                report.setWorkflowStage(null);
                report.setAssigneeId(null);
                reopened = true;
                if (source == Source.FE) {
                    report.setSlaDueAt(SlaPolicy.calcDueAt(report.getSeverity(), LocalDateTime.now()));
                    Long id = report.getId();
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            redisTemplate.delete("error-report:sla-overdue-notified:" + id);
                        }
                    });
                }
            } else {
                report.recordOccurrence(candidate.getOccurredAt(), source == Source.FE ? candidate.getUserComment() : null);
            }
        }
        if (source == Source.FE) {
            if (observedAffected >= 0) {
                report.setAffectedUserCount(Math.max(report.getAffectedUserCount(), observedAffected));
            }
            if (candidate.getUserComment() != null) report.setLatestUserComment(candidate.getUserComment());
        }
        if (!created && !reopened && source != Source.HEALTH) {
            ErrorReportSeverity severity = promotedSeverity(report, source);
            report.setSeverity(severity);
            if (severity.ordinal() > previousSeverity.ordinal()) {
                eventPublisher.publishEvent(new ErrorReportSeverityEscalatedEvent(report.getId(), previousSeverity, severity));
            }
        }
        if (reopened) {
            eventPublisher.publishEvent(new ErrorReportRegressionDetectedEvent(report.getId()));
        }
        if (source == Source.FE && (created || reopened)
                && report.getSeverity().ordinal() >= ErrorReportSeverity.HIGH.ordinal()) {
            if (created) eventPublisher.publishEvent(new ErrorReportRaisedEvent(report.getId(), true));
            eventPublisher.publishEvent(new ErrorReportAiAnalysisRequestedEvent(report.getId()));
        }
        repository.flush();
        hook.afterFlush(hash);
        if (source == Source.BACKEND && !reopened) {
            // 最後に追加する。commit 自体が失敗した際の余分一件は計画で許容する。
            var result = aggregator.addOccurrence(hash, report.getErrorMessage(), report.getSeverity());
            if (created && report.getSeverity().ordinal() >= ErrorReportSeverity.HIGH.ordinal()) {
                eventPublisher.publishEvent(new ErrorReportRaisedEvent(report.getId(),
                        result == ErrorReportAggregator.AggregationResult.FIRST_OCCURRENCE));
            }
        }
        return report;
    }

    private ErrorReportSeverity promotedSeverity(ErrorReportEntity report, Source source) {
        ErrorReportSeverity severity = report.getSeverity();
        if (report.getOccurrenceCount() >= 50) severity = ErrorReportSeverity.CRITICAL;
        else if (report.getOccurrenceCount() >= 10 && severity.ordinal() < ErrorReportSeverity.HIGH.ordinal()) {
            severity = ErrorReportSeverity.HIGH;
        }
        if (source == Source.FE) {
            if (report.getOccurrenceCount() >= 50 && report.getAffectedUserCount() <= 1
                    && severity == ErrorReportSeverity.CRITICAL) severity = ErrorReportSeverity.HIGH;
            if (report.getAffectedUserCount() >= 20 && severity == ErrorReportSeverity.MEDIUM) {
                severity = ErrorReportSeverity.HIGH;
            }
        }
        return severity;
    }
}
