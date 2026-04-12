package com.mannschaft.app.quickmemo.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.quickmemo.entity.PendingUploadEntity;
import com.mannschaft.app.quickmemo.repository.PendingUploadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Presigned URL 孤立オブジェクト削除バッチ。
 * 5分ごとに未確認・期限切れの pending_uploads を S3 から削除する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PendingUploadCleanupBatchService {

    private final PendingUploadRepository pendingUploadRepository;
    private final R2StorageService s3StorageService;
    private final AuditLogService auditLogService;

    @Scheduled(cron = "0 */5 * * * *")
    @Transactional
    public void execute() {
        LocalDateTime now = LocalDateTime.now();
        List<PendingUploadEntity> expired = pendingUploadRepository.findExpiredPendingUploads(now);

        if (expired.isEmpty()) return;

        int deletedCount = 0;
        for (PendingUploadEntity pending : expired) {
            try {
                s3StorageService.delete(pending.getS3Key());
                pendingUploadRepository.deleteByS3Key(pending.getS3Key());
                deletedCount++;
            } catch (Exception e) {
                log.error("孤立S3オブジェクト削除失敗: s3Key={}, error={}", pending.getS3Key(), e.getMessage());
            }
        }

        if (deletedCount > 0) {
            log.info("PendingUploadクリーンアップ: {}件削除", deletedCount);
            auditLogService.record("PENDING_UPLOAD_CLEANUP_BATCH", null, null, null, null, null, null, null,
                    "{\"deletedCount\":" + deletedCount + "}");
        }
    }
}
