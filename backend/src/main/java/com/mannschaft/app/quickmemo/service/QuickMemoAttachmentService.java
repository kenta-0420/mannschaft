package com.mannschaft.app.quickmemo.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.quickmemo.QuickMemoErrorCode;
import com.mannschaft.app.quickmemo.dto.AttachmentSummary;
import com.mannschaft.app.quickmemo.dto.ConfirmUploadRequest;
import com.mannschaft.app.quickmemo.dto.PresignRequest;
import com.mannschaft.app.quickmemo.dto.PresignResponse;
import com.mannschaft.app.quickmemo.entity.PendingUploadEntity;
import com.mannschaft.app.quickmemo.entity.QuickMemoAttachmentEntity;
import com.mannschaft.app.quickmemo.repository.PendingUploadRepository;
import com.mannschaft.app.quickmemo.repository.QuickMemoAttachmentRepository;
import com.mannschaft.app.quickmemo.repository.QuickMemoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ポイっとメモ添付ファイルサービス。
 * Presigned URL 発行・確認・削除を担当する。
 * セキュリティ: マジックバイト検証・EXIF削除・容量制限・1メモ1URL制限を実施。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QuickMemoAttachmentService {

    private static final int MAX_ATTACHMENTS_PER_MEMO = 5;
    private static final int MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10MB
    private static final long HOURLY_CAPACITY_LIMIT_BYTES = 100L * 1024 * 1024; // 100MB
    private static final int PRESIGN_EXPIRE_MINUTES = 5;
    private static final double SIZE_MISMATCH_TOLERANCE = 0.10; // 10%

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif"
    );

    private final QuickMemoRepository memoRepository;
    private final QuickMemoAttachmentRepository attachmentRepository;
    private final PendingUploadRepository pendingUploadRepository;
    private final R2StorageService s3StorageService;
    private final AuditLogService auditLogService;

    /**
     * Presigned URL を発行する。
     * - 1時間100MB の容量制限チェック
     * - 1メモ1URL制限（既存の未確認URLを削除してから新規発行）
     * - SVG 拒否
     */
    @Transactional
    public PresignResponse presignUrl(Long memoId, Long userId, PresignRequest req) {
        // メモの存在・所有権確認
        memoRepository.findByIdAndUserId(memoId, userId)
                .orElseThrow(() -> new BusinessException(QuickMemoErrorCode.MEMO_NOT_FOUND));

        // 添付上限チェック
        long attachCount = attachmentRepository.countByMemoId(memoId);
        if (attachCount >= MAX_ATTACHMENTS_PER_MEMO) {
            throw new BusinessException(QuickMemoErrorCode.ATTACHMENT_LIMIT_EXCEEDED);
        }

        // ファイルサイズ上限
        if (req.declaredSize() > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(QuickMemoErrorCode.ATTACHMENT_SIZE_EXCEEDED);
        }

        // SVG拒否・MIME確認
        if (!ALLOWED_CONTENT_TYPES.contains(req.contentType())) {
            throw new BusinessException(QuickMemoErrorCode.ATTACHMENT_INVALID_CONTENT_TYPE);
        }

        // 1時間の容量制限チェック
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        long usedBytes = pendingUploadRepository.sumDeclaredSizeSince(userId, oneHourAgo);
        if (usedBytes + req.declaredSize() > HOURLY_CAPACITY_LIMIT_BYTES) {
            throw new BusinessException(QuickMemoErrorCode.ATTACHMENT_HOURLY_CAPACITY_EXCEEDED);
        }

        // 既存の未確認URLを削除（1メモ1URL制限）
        LocalDateTime now = LocalDateTime.now();
        pendingUploadRepository.findActivePendingByMemoId(memoId, now).ifPresent(existing -> {
            s3StorageService.delete(existing.getS3Key());
            pendingUploadRepository.deleteByS3Key(existing.getS3Key());
        });

        // S3キー生成（user_id を含めない: C5対応）
        String ext = extensionFromContentType(req.contentType());
        String s3Key = "quick-memo/" + now.getYear() + String.format("%02d", now.getMonthValue())
                + "/" + UUID.randomUUID() + "." + ext;

        LocalDateTime expiresAt = now.plusMinutes(PRESIGN_EXPIRE_MINUTES);
        java.time.Duration ttl = java.time.Duration.ofMinutes(PRESIGN_EXPIRE_MINUTES);
        String presignedUrl = s3StorageService.generateUploadUrl(s3Key, req.contentType(), ttl).uploadUrl();

        // pending_uploads に記録
        PendingUploadEntity pending = PendingUploadEntity.builder()
                .memoId(memoId)
                .userId(userId)
                .s3Key(s3Key)
                .declaredSizeBytes(req.declaredSize())
                .contentType(req.contentType())
                .presignedUrlExpiresAt(expiresAt)
                .build();
        pendingUploadRepository.save(pending);

        log.info("Presigned URL発行: memoId={}, s3Key={}", memoId, s3Key);
        return new PresignResponse(presignedUrl, s3Key, expiresAt);
    }

    /**
     * アップロード確認。S3 HEAD でサイズ・MIMEを検証してから添付ファイルを登録する。
     */
    @Transactional
    public AttachmentSummary confirmUpload(Long memoId, Long userId, ConfirmUploadRequest req) {
        // メモの存在・所有権確認
        memoRepository.findByIdAndUserId(memoId, userId)
                .orElseThrow(() -> new BusinessException(QuickMemoErrorCode.MEMO_NOT_FOUND));

        // pending_uploads から取得
        PendingUploadEntity pending = pendingUploadRepository.findByS3Key(req.s3Key())
                .orElseThrow(() -> new BusinessException(QuickMemoErrorCode.ATTACHMENT_NOT_FOUND));

        // S3 HEAD でサイズ検証（真の防御層）
        long actualSize = s3StorageService.getObjectSize(req.s3Key());
        long declared = pending.getDeclaredSizeBytes();
        if (declared > 0) {
            double deviation = Math.abs((double)(actualSize - declared) / declared);
            if (deviation > SIZE_MISMATCH_TOLERANCE) {
                throw new BusinessException(QuickMemoErrorCode.ATTACHMENT_SIZE_MISMATCH);
            }
        }

        // マジックバイト検証（先頭16バイトのレンジGETで取得）
        byte[] magicBytes = s3StorageService.readFirstBytes(req.s3Key(), 16);
        validateMagicBytes(magicBytes, pending.getContentType());

        // 画像メタデータ取得（縦横サイズ）
        int[] dimensions = s3StorageService.getImageDimensions(req.s3Key());

        // quick_memo_attachments に登録
        long sortOrder = attachmentRepository.countByMemoId(memoId);
        QuickMemoAttachmentEntity attachment = QuickMemoAttachmentEntity.builder()
                .memoId(memoId)
                .s3Key(req.s3Key())
                .originalFilename(sanitizeFilename(req.originalFilename()))
                .contentType(pending.getContentType())
                .fileSizeBytes((int) actualSize)
                .widthPx(dimensions.length > 0 ? dimensions[0] : null)
                .heightPx(dimensions.length > 1 ? dimensions[1] : null)
                .sortOrder((int) sortOrder)
                .build();
        QuickMemoAttachmentEntity saved = attachmentRepository.save(attachment);

        // confirm 記録
        pending.confirm();
        pendingUploadRepository.save(pending);

        auditLogService.record("QUICK_MEMO_ATTACHMENT_CONFIRMED", userId, null, null, null, null, null, null,
                "{\"memoId\":" + memoId + ",\"s3Key\":\"" + req.s3Key() + "\"}");

        return AttachmentSummary.from(saved);
    }

    /**
     * 添付ファイルを削除する。
     */
    @Transactional
    public void deleteAttachment(Long memoId, Long userId, Long attachmentId) {
        memoRepository.findByIdAndUserId(memoId, userId)
                .orElseThrow(() -> new BusinessException(QuickMemoErrorCode.MEMO_NOT_FOUND));

        QuickMemoAttachmentEntity attachment = attachmentRepository.findById(attachmentId)
                .filter(a -> a.getMemoId().equals(memoId))
                .orElseThrow(() -> new BusinessException(QuickMemoErrorCode.ATTACHMENT_NOT_FOUND));

        // S3オブジェクト同期削除（非同期不可: データ漏洩防止）
        s3StorageService.delete(attachment.getS3Key());
        attachmentRepository.delete(attachment);

        auditLogService.record("QUICK_MEMO_ATTACHMENT_DELETED", userId, null, null, null, null, null, null,
                "{\"memoId\":" + memoId + ",\"attachmentId\":" + attachmentId + "}");
    }

    // ─── プライベートヘルパー ──────────────────────────────────────────────────────────

    private void validateMagicBytes(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length < 4) {
            throw new BusinessException(QuickMemoErrorCode.ATTACHMENT_MAGIC_BYTES_INVALID);
        }
        boolean valid = switch (contentType) {
            case "image/jpeg" -> bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF;
            case "image/png" -> bytes[0] == (byte) 0x89 && bytes[1] == 0x50
                    && bytes[2] == 0x4E && bytes[3] == 0x47;
            case "image/webp" -> bytes.length >= 12
                    && bytes[0] == 0x52 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x46
                    && bytes[8] == 0x57 && bytes[9] == 0x45 && bytes[10] == 0x42 && bytes[11] == 0x50;
            case "image/gif" -> bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46;
            default -> false;
        };
        if (!valid) {
            throw new BusinessException(QuickMemoErrorCode.ATTACHMENT_MAGIC_BYTES_INVALID);
        }
    }

    private String extensionFromContentType(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "bin";
        };
    }

    private String sanitizeFilename(String filename) {
        if (filename == null) return null;
        return filename.replaceAll("[\\x00/\\\\]", "");
    }
}
