package com.mannschaft.app.timetable.notes.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.quota.StorageFeatureType;
import com.mannschaft.app.common.storage.quota.StorageQuotaExceededException;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.timetable.notes.dto.AttachmentConfirmRequest;
import com.mannschaft.app.timetable.notes.dto.AttachmentPresignRequest;
import com.mannschaft.app.timetable.notes.dto.AttachmentPresignResponse;
import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteAttachmentEntity;
import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteEntity;
import com.mannschaft.app.timetable.notes.repository.TimetableSlotUserNoteAttachmentRepository;
import com.mannschaft.app.timetable.personal.PersonalTimetableErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F03.15 Phase 3 メモ添付ファイルサービス。
 *
 * <p>R2 ストレージへの presign 発行 / confirm（メタ確定 + magic byte 検証） / download URL 発行 /
 * 論理削除を担当する。1メモあたり最大5件、サイズ最大5MB。</p>
 *
 * <p><b>F13 Phase 4-α 改修</b>: 従来の「1ユーザー累計 100MB 直書きクォータ」を廃止し、
 * F13 統合クォータサービス（{@link StorageQuotaService}）に接続した。スコープは PERSONAL（投稿者本人）、
 * feature_type は {@link StorageFeatureType#PERSONAL_TIMETABLE_NOTES}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimetableSlotUserNoteAttachmentService {

    /** 1 メモあたりの添付件数上限（設計書 §3）。 */
    public static final int MAX_ATTACHMENTS_PER_NOTE = 5;
    /** 添付ファイル単体の最大サイズ（5MB）。 */
    public static final long MAX_ATTACHMENT_SIZE_BYTES = 5L * 1024 * 1024;
    /** Pre-signed URL の TTL（5分）。 */
    public static final Duration PRESIGN_TTL = Duration.ofMinutes(5);

    /** F13 Phase 4-α: storage_usage_logs.reference_type に記録するテーブル名。 */
    private static final String REFERENCE_TYPE = "timetable_slot_user_note_attachments";

    /** 許容する MIME タイプとマジックバイトの先頭マーカーのマップ。 */
    public static final Map<String, byte[][]> ALLOWED_MIME_MAGIC_BYTES = Map.of(
            "image/jpeg", new byte[][]{{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}},
            "image/png", new byte[][]{{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}},
            "image/webp", new byte[][]{{0x52, 0x49, 0x46, 0x46}}, // "RIFF" -- + "WEBP" at offset 8
            "image/heic", new byte[][]{
                    {0x66, 0x74, 0x79, 0x70, 0x68, 0x65, 0x69, 0x63}, // "ftypheic" at offset 4
                    {0x66, 0x74, 0x79, 0x70, 0x68, 0x65, 0x69, 0x78}, // "ftypheix"
                    {0x66, 0x74, 0x79, 0x70, 0x6D, 0x69, 0x66, 0x31}  // "ftypmif1"
            },
            "application/pdf", new byte[][]{{0x25, 0x50, 0x44, 0x46}} // "%PDF"
    );

    /** R2 オブジェクトキー プレフィックステンプレート。 */
    private static final String OBJECT_KEY_TEMPLATE = "user/%d/timetable-notes/%s.%s";

    private final TimetableSlotUserNoteAttachmentRepository attachmentRepository;
    private final TimetableSlotUserNoteService noteService;
    private final R2StorageService r2StorageService;
    /** F03.15 Phase 5: 削除時の F11 監査ログ発火に使用。 */
    private final AuditLogService auditLogService;
    /** F13 Phase 4-α: 統合ストレージクォータサービス。 */
    private final StorageQuotaService storageQuotaService;

    /**
     * Pre-signed PUT URL を発行する。
     *
     * <p>F13 Phase 4-α: ユーザー累計クォータの判定は {@link StorageQuotaService#checkQuota} に委譲する。
     * 容量超過時は固有エラーコード {@link PersonalTimetableErrorCode#ATTACHMENT_QUOTA_EXCEEDED} に
     * 変換してから再スローする（既存 API 契約 429 を維持）。</p>
     */
    @Transactional
    public AttachmentPresignResponse presign(Long noteId, Long userId, AttachmentPresignRequest req) {
        TimetableSlotUserNoteEntity note = noteService.getMine(noteId, userId);

        if (req.sizeBytes() == null || req.sizeBytes() <= 0
                || req.sizeBytes() > MAX_ATTACHMENT_SIZE_BYTES) {
            throw new BusinessException(PersonalTimetableErrorCode.ATTACHMENT_SIZE_EXCEEDED);
        }
        if (!ALLOWED_MIME_MAGIC_BYTES.containsKey(req.contentType())) {
            throw new BusinessException(PersonalTimetableErrorCode.ATTACHMENT_UNSUPPORTED_TYPE);
        }
        long current = attachmentRepository.countByNoteIdAndDeletedAtIsNull(note.getId());
        if (current >= MAX_ATTACHMENTS_PER_NOTE) {
            throw new BusinessException(PersonalTimetableErrorCode.ATTACHMENT_LIMIT_EXCEEDED);
        }

        // F13 Phase 4-α: 統合クォータサービスでチェック（PERSONAL スコープ = 投稿者本人）
        try {
            storageQuotaService.checkQuota(StorageScopeType.PERSONAL, userId, req.sizeBytes());
        } catch (StorageQuotaExceededException e) {
            log.info("メモ添付クォータ超過: userId={}, requested={}, used={}, included={}",
                    userId, e.getRequestedBytes(), e.getUsedBytes(), e.getIncludedBytes());
            throw new BusinessException(PersonalTimetableErrorCode.ATTACHMENT_QUOTA_EXCEEDED, e);
        }

        String ext = resolveExtension(req.contentType());
        String key = String.format(OBJECT_KEY_TEMPLATE, userId, UUID.randomUUID(), ext);
        PresignedUploadResult result = r2StorageService.generateUploadUrl(
                key, req.contentType(), PRESIGN_TTL);
        log.info("メモ添付の pre-signed URL を発行しました: noteId={}, userId={}, key={}",
                noteId, userId, key);
        return new AttachmentPresignResponse(result.uploadUrl(), key, PRESIGN_TTL.toSeconds());
    }

    /**
     * アップロード完了通知（メタ確定 + magic byte 検証 + 冪等性）。
     *
     * <p>同一 r2_object_key に対する 2 回目以降は既存レコードを返却する（重複 INSERT しない）。</p>
     *
     * <p>F13 Phase 4-α: R2 検証成功 + DB 永続化成功直後に
     * {@link StorageQuotaService#recordUpload} で使用量を加算する。</p>
     */
    @Transactional
    public TimetableSlotUserNoteAttachmentEntity confirm(
            Long noteId, Long userId, AttachmentConfirmRequest req,
            AttachmentPresignRequest originalRequest) {
        TimetableSlotUserNoteEntity note = noteService.getMine(noteId, userId);

        // R2 キーが本ユーザーのプレフィックスに属することを検証
        String expectedPrefix = "user/" + userId + "/timetable-notes/";
        if (!req.r2ObjectKey().startsWith(expectedPrefix)) {
            throw new BusinessException(PersonalTimetableErrorCode.NOTE_SLOT_NOT_OWNED);
        }

        // 冪等性チェック
        var existing = attachmentRepository.findByR2ObjectKey(req.r2ObjectKey());
        if (existing.isPresent()) {
            return existing.get();
        }

        // R2 上の存在確認 + サイズ検証
        if (!r2StorageService.objectExists(req.r2ObjectKey())) {
            throw new BusinessException(PersonalTimetableErrorCode.ATTACHMENT_OBJECT_NOT_FOUND);
        }
        long actualSize = r2StorageService.getObjectSize(req.r2ObjectKey());
        if (actualSize > MAX_ATTACHMENT_SIZE_BYTES) {
            r2StorageService.delete(req.r2ObjectKey());
            throw new BusinessException(PersonalTimetableErrorCode.ATTACHMENT_SIZE_EXCEEDED);
        }

        String contentType = originalRequest != null ? originalRequest.contentType()
                : guessContentTypeFromExtension(req.r2ObjectKey());
        if (!ALLOWED_MIME_MAGIC_BYTES.containsKey(contentType)) {
            r2StorageService.delete(req.r2ObjectKey());
            throw new BusinessException(PersonalTimetableErrorCode.ATTACHMENT_UNSUPPORTED_TYPE);
        }

        // Magic byte 検証（先頭16バイト）
        byte[] head = r2StorageService.readFirstBytes(req.r2ObjectKey(), 16);
        if (!matchesMagicBytes(contentType, head)) {
            r2StorageService.delete(req.r2ObjectKey());
            throw new BusinessException(PersonalTimetableErrorCode.ATTACHMENT_MAGIC_BYTE_MISMATCH);
        }

        String fileName = originalRequest != null
                ? originalRequest.fileName()
                : extractFileName(req.r2ObjectKey());

        TimetableSlotUserNoteAttachmentEntity entity = TimetableSlotUserNoteAttachmentEntity.builder()
                .noteId(note.getId())
                .userId(userId)
                .r2ObjectKey(req.r2ObjectKey())
                .originalFilename(fileName)
                .mimeType(contentType)
                .sizeBytes(actualSize)
                .build();
        TimetableSlotUserNoteAttachmentEntity saved = attachmentRepository.save(entity);

        // F13 Phase 4-α: 使用量加算
        storageQuotaService.recordUpload(
                StorageScopeType.PERSONAL, userId, actualSize,
                StorageFeatureType.PERSONAL_TIMETABLE_NOTES,
                REFERENCE_TYPE, saved.getId(), userId);

        log.info("メモ添付を登録しました: id={}, noteId={}, userId={}, size={}",
                saved.getId(), noteId, userId, actualSize);
        return saved;
    }

    /**
     * ダウンロード用 Pre-signed URL を発行する。
     */
    public String generateDownloadUrl(Long attachmentId, Long userId) {
        TimetableSlotUserNoteAttachmentEntity entity = attachmentRepository
                .findByIdAndUserId(attachmentId, userId)
                .orElseThrow(() -> new BusinessException(
                        PersonalTimetableErrorCode.ATTACHMENT_NOT_FOUND));
        if (entity.getDeletedAt() != null) {
            throw new BusinessException(PersonalTimetableErrorCode.ATTACHMENT_NOT_FOUND);
        }
        // キープレフィックスの再検証
        String expectedPrefix = "user/" + userId + "/";
        if (!entity.getR2ObjectKey().startsWith(expectedPrefix)) {
            throw new BusinessException(PersonalTimetableErrorCode.ATTACHMENT_NOT_FOUND);
        }
        return r2StorageService.generateDownloadUrl(entity.getR2ObjectKey(), PRESIGN_TTL);
    }

    /**
     * 添付ファイルを論理削除する。R2 オブジェクトは週次バッチで物理削除する。
     *
     * <p>F13 Phase 4-α: 論理削除完了後に {@link StorageQuotaService#recordDeletion}
     * で使用量を減算する。</p>
     */
    @Transactional
    public void delete(Long attachmentId, Long userId) {
        TimetableSlotUserNoteAttachmentEntity entity = attachmentRepository
                .findByIdAndUserId(attachmentId, userId)
                .orElseThrow(() -> new BusinessException(
                        PersonalTimetableErrorCode.ATTACHMENT_NOT_FOUND));
        if (entity.getDeletedAt() != null) {
            return; // 既に削除済み
        }
        long sizeBytes = entity.getSizeBytes() != null ? entity.getSizeBytes() : 0L;
        entity.softDelete();
        attachmentRepository.save(entity);

        // F13 Phase 4-α: 使用量減算
        storageQuotaService.recordDeletion(
                StorageScopeType.PERSONAL, userId, sizeBytes,
                StorageFeatureType.PERSONAL_TIMETABLE_NOTES,
                REFERENCE_TYPE, entity.getId(), userId);

        // F03.15 Phase 5: 監査ログ発火
        auditLogService.record(
                "personal_timetable.attachment_deleted",
                userId, null, null, null, null, null, null,
                String.format(
                        "{\"source\":\"PERSONAL_TIMETABLE\",\"source_id\":%d,\"note_id\":%d}",
                        attachmentId, entity.getNoteId()));

        log.info("メモ添付を論理削除しました: id={}, userId={}", attachmentId, userId);
    }

    /**
     * 指定メモの添付一覧（未削除のみ）。
     */
    public List<TimetableSlotUserNoteAttachmentEntity> listForNote(Long noteId, Long userId) {
        noteService.getMine(noteId, userId);
        return attachmentRepository.findByNoteIdAndDeletedAtIsNullOrderByCreatedAtAsc(noteId);
    }

    // ---- Private helpers ----

    private String resolveExtension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/heic" -> "heic";
            case "application/pdf" -> "pdf";
            default -> "bin";
        };
    }

    private String guessContentTypeFromExtension(String key) {
        int dot = key.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = key.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "heic" -> "image/heic";
            case "pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }

    private String extractFileName(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }

    /**
     * 先頭16バイトから MIME に対応するマジックバイトを検証する。
     */
    private boolean matchesMagicBytes(String contentType, byte[] head) {
        byte[][] markers = ALLOWED_MIME_MAGIC_BYTES.get(contentType);
        if (markers == null) return false;

        // image/heic / mp4 系は ftyp サブタイプ判定が offset 4 から
        if ("image/heic".equals(contentType)) {
            if (head.length < 12) return false;
            byte[] ftyp = Arrays.copyOfRange(head, 4, 12);
            for (byte[] marker : markers) {
                if (Arrays.equals(ftyp, marker)) return true;
            }
            return false;
        }
        // image/webp は "RIFF" + "WEBP" at offset 8
        if ("image/webp".equals(contentType)) {
            if (head.length < 12) return false;
            return head[0] == 0x52 && head[1] == 0x49 && head[2] == 0x46 && head[3] == 0x46
                    && head[8] == 0x57 && head[9] == 0x45 && head[10] == 0x42 && head[11] == 0x50;
        }
        for (byte[] marker : markers) {
            if (head.length < marker.length) continue;
            if (Arrays.equals(Arrays.copyOf(head, marker.length), marker)) return true;
        }
        return false;
    }
}
