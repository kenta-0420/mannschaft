package com.mannschaft.app.files.service;

import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.files.entity.MultipartUploadSessionEntity;
import com.mannschaft.app.files.repository.MultipartUploadSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 補償abortに失敗したmultipartを別Txで保持し、再試行可能にするサービス。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultipartUploadCleanupService {

    private static final String ABORT_PENDING = "ABORT_PENDING";

    private final MultipartUploadSessionRepository repository;
    private final R2StorageService r2StorageService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAbortPending(String uploadId, String r2Key, String feature, String scopeType,
                                 Long scopeId, Long ownerId, String contentType) {
        repository.save(MultipartUploadSessionEntity.builder()
                .uploadId(uploadId).r2Key(r2Key).feature(feature).scopeType(scopeType).scopeId(scopeId)
                .uploaderId(ownerId).contentType(contentType).status(ABORT_PENDING)
                .expiresAt(LocalDateTime.now()).build());
    }

    /** 期限到来した補償対象を一件ずつabortする。失敗行は状態を残して次回へ回す。 */
    @Transactional
    public int retryPendingAborts(LocalDateTime now) {
        int succeeded = 0;
        for (MultipartUploadSessionEntity session : repository
                .findByStatusAndExpiresAtBefore(ABORT_PENDING, now)) {
            try {
                r2StorageService.abortMultipartUpload(session.getR2Key(), session.getUploadId());
                repository.save(session.toBuilder().status("ABORTED").build());
                succeeded++;
            } catch (RuntimeException e) {
                log.warn("Multipart補償abortを再試行します: uploadId={}, fileKey={}",
                        session.getUploadId(), session.getR2Key(), e);
            }
        }
        return succeeded;
    }
}
