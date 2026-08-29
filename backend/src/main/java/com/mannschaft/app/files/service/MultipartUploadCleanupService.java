package com.mannschaft.app.files.service;

import com.mannschaft.app.common.storage.R2StorageService;
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

import java.time.LocalDateTime;

/** 補償abortに失敗したmultipartを別Txで保持し、再試行可能にするサービス。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultipartUploadCleanupService {

    private static final String ABORT_PENDING = "ABORT_PENDING";

    private final MultipartAbortCleanupRepository repository;
    private final R2StorageService r2StorageService;
    @Value("${mannschaft.storage.multipart-cleanup-max-attempts:10}")
    private int maxAttempts;
    private static final int LEASE_MINUTES = 10;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAbortPending(String uploadId, String r2Key, String feature, String scopeType,
                                 Long scopeId, Long ownerId, String contentType) {
        repository.save(MultipartAbortCleanupEntity.builder().uploadId(uploadId).r2Key(r2Key)
                .ownerId(ownerId).contentType(contentType).feature(feature).scopeType(scopeType).scopeId(scopeId)
                .status(ABORT_PENDING).nextAttemptAt(LocalDateTime.now()).attemptCount(0).build());
    }

    /** 期限到来した補償対象を一件ずつabortする。失敗行は状態を残して次回へ回す。 */
    public int retryPendingAborts(LocalDateTime now) {
        int succeeded = 0;
        repository.releaseExpiredClaims(now);
        for (MultipartAbortCleanupEntity session : repository
                .findByStatusAndNextAttemptAtBefore(ABORT_PENDING, now)) {
            try {
                if (session.getAttemptCount() >= maxAttempts) {
                    repository.save(session.toBuilder().status("DEAD_LETTER").build());
                    log.error("Multipart補償abortをdead-letterへ隔離しました: uploadId={}", session.getUploadId());
                    continue;
                }
                if (repository.claim(session.getId(), now, now.plusMinutes(LEASE_MINUTES)) == 0) {
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
                        .nextAttemptAt(now.plusMinutes(5)).attemptCount(session.getAttemptCount() + 1).build());
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
    public void scheduledRetry() {
        retryPendingAborts(LocalDateTime.now());
    }
}
