package com.mannschaft.app.workflow.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.storage.FileTypeValidator;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.workflow.WorkflowErrorCode;
import com.mannschaft.app.workflow.WorkflowMapper;
import com.mannschaft.app.workflow.WorkflowScopes;
import com.mannschaft.app.workflow.dto.WorkflowAttachmentPresignRequest;
import com.mannschaft.app.workflow.dto.WorkflowAttachmentPresignResponse;
import com.mannschaft.app.workflow.dto.WorkflowAttachmentRegisterRequest;
import com.mannschaft.app.workflow.dto.WorkflowAttachmentResponse;
import com.mannschaft.app.workflow.entity.WorkflowRequestAttachmentEntity;
import com.mannschaft.app.workflow.entity.WorkflowRequestEntity;
import com.mannschaft.app.workflow.repository.WorkflowRequestAttachmentRepository;
import com.mannschaft.app.workflow.repository.WorkflowRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ワークフロー申請添付ファイルサービス。
 *
 * <p>F05.6 Phase 11 第二陣（2-γ）で追加。Pre-signed URL 発行・添付登録・添付削除を担当する。</p>
 *
 * <ul>
 *   <li>S3 key prefix: {@code workflow-attachments/{requestId}/{uuid}.{ext}}</li>
 *   <li>Pre-signed URL 有効期限: 15 分（アップロード時）</li>
 *   <li>許可 MIME タイプ: PDF / JPEG / PNG / WebP / GIF / XLSX / DOCX / CSV</li>
 *   <li>ファイルサイズ上限: 1 ファイル 20 MB（DTO バリデーションで担保）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowRequestAttachmentService {

    private static final Duration PRESIGN_TTL = Duration.ofMinutes(15);

    /**
     * 許可 MIME タイプ（F05.6 §3 workflow_request_attachments 制約に基づく）。
     * {@link FileTypeValidator} の定数を合成して使用する。
     */
    private static final Set<String> ALLOWED_CONTENT_TYPES;

    static {
        var merged = new java.util.HashSet<String>();
        merged.addAll(FileTypeValidator.ALLOWED_IMAGE_TYPES);
        merged.addAll(FileTypeValidator.ALLOWED_DOCUMENT_TYPES);
        ALLOWED_CONTENT_TYPES = java.util.Collections.unmodifiableSet(merged);
    }

    private final WorkflowRequestAttachmentRepository attachmentRepository;
    private final WorkflowRequestRepository requestRepository;
    private final WorkflowMapper workflowMapper;
    private final R2StorageService r2StorageService;
    private final AccessControlService accessControlService;

    /**
     * 申請の添付ファイル一覧を取得する（Wave 2 トランシェ2C で Controller の直リポジトリ参照を移管）。
     *
     * <p>認可: 申請者本人、または申請スコープのメンバー/ADMIN のみ（それ以外は 404 秘匿）。</p>
     *
     * @param requestId     申請 ID
     * @param currentUserId 操作者ユーザー ID
     * @return 添付ファイルレスポンスリスト
     */
    public List<WorkflowAttachmentResponse> listAttachments(Long requestId, Long currentUserId) {
        findVisibleRequestOrThrow(requestId, currentUserId);
        return workflowMapper.toAttachmentResponseList(
                attachmentRepository.findByRequestIdOrderByCreatedAtAsc(requestId));
    }

    /**
     * 添付ファイルのアップロード用 Pre-signed URL を発行する。
     *
     * @param requestId   申請 ID
     * @param currentUserId 操作者ユーザー ID
     * @param request     Pre-signed リクエスト
     * @return Pre-signed URL レスポンス
     */
    public WorkflowAttachmentPresignResponse presignUpload(
            Long requestId, Long currentUserId, WorkflowAttachmentPresignRequest request) {
        // 1. 申請存在確認＋可視性検証（非所属者は 404 秘匿・Wave 2 トランシェ2C）
        WorkflowRequestEntity requestEntity = findVisibleRequestOrThrow(requestId, currentUserId);

        // 2. MIME タイプ検証（ブロックリスト優先 → ホワイトリスト）
        if (FileTypeValidator.isBlocked(request.contentType())) {
            log.warn("ワークフロー添付 presign-upload: ブロック対象 contentType={}, requestId={}",
                    request.contentType(), requestId);
            throw new BusinessException(WorkflowErrorCode.INVALID_FIELD_VALUE);
        }
        if (!FileTypeValidator.isAllowed(request.contentType(), ALLOWED_CONTENT_TYPES)) {
            log.warn("ワークフロー添付 presign-upload: 許可外 contentType={}, requestId={}",
                    request.contentType(), requestId);
            throw new BusinessException(WorkflowErrorCode.INVALID_FIELD_VALUE);
        }

        // 3. fileKey 生成: workflow-attachments/{requestId}/{uuid}.{ext}
        String ext = resolveExtension(request.contentType());
        String fileKey = "workflow-attachments/" + requestEntity.getId() + "/"
                + UUID.randomUUID() + "." + ext;

        // 4. Pre-signed URL 発行
        PresignedUploadResult result = r2StorageService.generateUploadUrl(
                fileKey, request.contentType(), PRESIGN_TTL);

        log.info("ワークフロー添付 presign-upload 発行: requestId={}, userId={}, fileKey={}",
                requestId, currentUserId, fileKey);

        return new WorkflowAttachmentPresignResponse(
                result.uploadUrl(), fileKey, result.expiresInSeconds());
    }

    /**
     * Pre-signed URL でアップロード完了した添付ファイルを登録する。
     *
     * @param requestId   申請 ID
     * @param currentUserId 操作者ユーザー ID
     * @param request     登録リクエスト
     * @return 添付ファイルレスポンス
     */
    @Transactional
    public WorkflowAttachmentResponse registerAttachment(
            Long requestId, Long currentUserId, WorkflowAttachmentRegisterRequest request) {
        // 1. 申請存在確認＋可視性検証（非所属者は 404 秘匿・Wave 2 トランシェ2C）
        WorkflowRequestEntity requestEntity = findVisibleRequestOrThrow(requestId, currentUserId);

        // 2. fileKey 整合性チェック（prefix が workflow-attachments/{requestId}/ で始まること）
        String expectedPrefix = "workflow-attachments/" + requestEntity.getId() + "/";
        if (!request.fileKey().startsWith(expectedPrefix)) {
            log.warn("ワークフロー添付登録: fileKey prefix 不一致 fileKey={}, expected={}",
                    request.fileKey(), expectedPrefix);
            throw new BusinessException(WorkflowErrorCode.INVALID_FIELD_VALUE);
        }

        // 3. INSERT
        WorkflowRequestAttachmentEntity entity = WorkflowRequestAttachmentEntity.builder()
                .requestId(requestEntity.getId())
                .fileKey(request.fileKey())
                .originalFilename(request.originalFilename())
                .fileSize(request.fileSize())
                .uploadedBy(currentUserId)
                .build();

        WorkflowRequestAttachmentEntity saved = attachmentRepository.save(entity);
        log.info("ワークフロー添付登録: requestId={}, attachmentId={}, userId={}",
                requestId, saved.getId(), currentUserId);

        return workflowMapper.toAttachmentResponse(saved);
    }

    /**
     * 添付ファイルを削除する（物理削除 + R2 オブジェクト削除）。
     *
     * <p>Entity は {@code deleted_at} カラムを持たないため、物理削除する。
     * R2 オブジェクトもベストエフォートで削除する。</p>
     *
     * @param requestId    申請 ID
     * @param attachmentId 添付ファイル ID
     * @param currentUserId 操作者ユーザー ID
     */
    @Transactional
    public void deleteAttachment(Long requestId, Long attachmentId, Long currentUserId) {
        // 1. 申請存在確認＋可視性検証（非所属者は 404 秘匿・Wave 2 トランシェ2C）
        WorkflowRequestEntity requestEntity = findVisibleRequestOrThrow(requestId, currentUserId);

        // 2. 添付存在確認
        WorkflowRequestAttachmentEntity entity = attachmentRepository
                .findByIdAndRequestId(attachmentId, requestId)
                .orElseThrow(() -> new BusinessException(WorkflowErrorCode.ATTACHMENT_NOT_FOUND));

        // 3. 削除権限: アップロード者本人・申請者本人・entity 由来スコープの ADMIN のみ（403）
        boolean uploader = currentUserId != null && currentUserId.equals(entity.getUploadedBy());
        boolean requester = currentUserId != null && currentUserId.equals(requestEntity.getRequestedBy());
        if (!uploader && !requester && !accessControlService.isAdminOrAbove(
                currentUserId, requestEntity.getScopeId(),
                WorkflowScopes.canonical(requestEntity.getScopeType()))) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        // 4. R2 オブジェクト削除（失敗してもログのみ。DB との整合性は将来のクリーニングバッチで担保）
        try {
            r2StorageService.delete(entity.getFileKey());
        } catch (Exception e) {
            log.warn("ワークフロー添付 R2 削除失敗（DB 削除は継続）: fileKey={}, error={}",
                    entity.getFileKey(), e.getMessage());
        }

        // 5. DB 物理削除
        attachmentRepository.delete(entity);
        log.info("ワークフロー添付削除: requestId={}, attachmentId={}, userId={}",
                requestId, attachmentId, currentUserId);
    }

    /**
     * 親申請を取得し、可視性（申請者本人 or entity 由来スコープのメンバー/ADMIN）を検証する。
     * いずれでもない場合は 404（REQUEST_NOT_FOUND）で存在秘匿する（★BOLA厳禁★・Wave 2 トランシェ2C）。
     */
    private WorkflowRequestEntity findVisibleRequestOrThrow(Long requestId, Long actorUserId) {
        WorkflowRequestEntity requestEntity = requestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(WorkflowErrorCode.REQUEST_NOT_FOUND));
        if (actorUserId != null && actorUserId.equals(requestEntity.getRequestedBy())) {
            return requestEntity;
        }
        String canonicalScope = WorkflowScopes.canonical(requestEntity.getScopeType());
        if (accessControlService.isMember(actorUserId, requestEntity.getScopeId(), canonicalScope)
                || accessControlService.isAdminOrAbove(actorUserId, requestEntity.getScopeId(), canonicalScope)) {
            return requestEntity;
        }
        throw new BusinessException(WorkflowErrorCode.REQUEST_NOT_FOUND);
    }

    /**
     * Content-Type から拡張子を解決する。
     */
    private String resolveExtension(String contentType) {
        return switch (contentType) {
            case "application/pdf" -> "pdf";
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "text/csv" -> "csv";
            default -> "bin";
        };
    }
}
