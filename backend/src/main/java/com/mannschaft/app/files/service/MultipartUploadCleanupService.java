package com.mannschaft.app.files.service;

import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.files.entity.MultipartAbortCleanupEntity;
import com.mannschaft.app.files.repository.MultipartAbortCleanupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;

/** 補償abortに失敗したmultipartを別Txで保持し、再試行可能にするサービス。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultipartUploadCleanupService {

    private static final String ABORT_PENDING = "ABORT_PENDING";

    private final MultipartAbortCleanupRepository repository;
    private final R2StorageService r2StorageService;
    @org.springframework.beans.factory.annotation.Qualifier("utcClock")
    private final Clock clock;
    @Value("${mannschaft.storage.multipart-cleanup-max-attempts:10}")
    private int maxAttempts;
    @Value("${mannschaft.storage.multipart-cleanup-retention-days:30}")
    private int retentionDays;
    private static final int LEASE_MINUTES = 10;

    @PostConstruct
    void validateConfiguration() {
        if (maxAttempts < 1) {
            throw new IllegalStateException("multipart-cleanup-max-attemptsは1以上で指定してください");
        }
        if (retentionDays < 1) {
            throw new IllegalStateException("multipart-cleanup-retention-daysは1以上で指定してください");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAbortPending(String uploadId, String r2Key, String feature, String scopeType,
                                 Long scopeId, Long ownerId, String contentType) {
        repository.save(MultipartAbortCleanupEntity.builder().uploadId(uploadId).r2Key(r2Key)
                .ownerId(ownerId).contentType(contentType).feature(feature).scopeType(scopeType).scopeId(scopeId)
                .status(ABORT_PENDING).nextAttemptAt(Instant.now(clock)).attemptCount(0).build());
    }

    /** 期限到来した補償対象を一件ずつabortする。失敗行は状態を残して次回へ回す。 */
    public int retryPendingAborts(Instant now) {
        int succeeded = 0;
        repository.releaseExpiredClaims(now);
        repository.findByStatusAndDeadLetteredAtBefore("DEAD_LETTER", now.minus(Duration.ofDays(retentionDays)))
                .forEach(repository::delete);
        for (MultipartAbortCleanupEntity session : repository
                .findByStatusAndNextAttemptAtBefore(ABORT_PENDING, now)) {
            try {
                if (session.getAttemptCount() >= maxAttempts) {
                    repository.save(session.toBuilder().status("DEAD_LETTER").deadLetteredAt(now).build());
                    log.error("Multipart補償abortをdead-letterへ隔離しました: uploadId={}", session.getUploadId());
                    continue;
                }
                if (repository.claim(session.getId(), now, now.plus(Duration.ofMinutes(LEASE_MINUTES))) == 0) {
                    continue;
                }
                r2StorageService.abortMultipartUpload(session.getR2Key(), session.getUploadId());
                repository.delete(session);
                succeeded++;
            } catch (RuntimeException e) {
                if (isNoSuchUpload(e)) {
                    repository.delete(session);
                    succeeded++;
                    continue;
                }
                repository.save(session.toBuilder().status(ABORT_PENDING).leaseUntil(null)
                        .nextAttemptAt(now.plus(Duration.ofMinutes(5))).attemptCount(session.getAttemptCount() + 1).build());
                log.warn("Multipart補償abortを再試行します: uploadId={}, fileKey={}",
                        session.getUploadId(), session.getR2Key(), e);
            }
        }
        return succeeded;
    }

    private boolean isNoSuchUpload(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getClass().getSimpleName().equals("NoSuchUploadException")) {
                return true;
            }
        }
        return false;
    }

    @Scheduled(fixedDelayString = "${mannschaft.storage.multipart-cleanup-interval-ms:300000}")
    @SchedulerLock(name = "multipartAbortCleanup", lockAtMostFor = "PT9M", lockAtLeastFor = "PT10S")
    @BatchEndpoint(name = "multipart-abort-cleanup", description = "失敗したMultipart abortを再試行し不要な台帳を整理する")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "停止するとabort補償が回収されずR2上に孤児Multipartが残るため常時実行する")
    public void scheduledRetry() {
        retryPendingAborts(Instant.now(clock));
    }
}
