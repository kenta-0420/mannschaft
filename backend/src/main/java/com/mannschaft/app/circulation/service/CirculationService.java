package com.mannschaft.app.circulation.service;

import com.mannschaft.app.circulation.CirculationErrorCode;
import com.mannschaft.app.circulation.CirculationMapper;
import com.mannschaft.app.circulation.CirculationMode;
import com.mannschaft.app.circulation.CirculationPriority;
import com.mannschaft.app.circulation.CirculationStatus;
import com.mannschaft.app.circulation.StampDisplayStyle;
import com.mannschaft.app.circulation.dto.AddRecipientsRequest;
import com.mannschaft.app.circulation.dto.AttachmentResponse;
import com.mannschaft.app.circulation.dto.CreateAttachmentRequest;
import com.mannschaft.app.circulation.dto.CreateDocumentRequest;
import com.mannschaft.app.circulation.dto.DocumentResponse;
import com.mannschaft.app.circulation.dto.DocumentStatsResponse;
import com.mannschaft.app.circulation.dto.RecipientEntry;
import com.mannschaft.app.circulation.dto.RecipientResponse;
import com.mannschaft.app.circulation.dto.UpdateDocumentRequest;
import com.mannschaft.app.circulation.entity.CirculationAttachmentEntity;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import com.mannschaft.app.circulation.repository.CirculationAttachmentRepository;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.circulation.repository.CirculationRecipientRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 回覧板サービス。文書CRUD・受信者管理・添付ファイル管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CirculationService {

    private final CirculationDocumentRepository documentRepository;
    private final CirculationRecipientRepository recipientRepository;
    private final CirculationAttachmentRepository attachmentRepository;
    private final CirculationMapper circulationMapper;

    /**
     * 文書一覧をページング取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param status    ステータスフィルタ（null の場合は全件）
     * @param pageable  ページング情報
     * @return 文書レスポンスのページ
     */
    public Page<DocumentResponse> listDocuments(String scopeType, Long scopeId, String status, Pageable pageable) {
        Page<CirculationDocumentEntity> page;
        if (status != null) {
            CirculationStatus circulationStatus = CirculationStatus.valueOf(status);
            page = documentRepository.findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
                    scopeType, scopeId, circulationStatus, pageable);
        } else {
            page = documentRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                    scopeType, scopeId, pageable);
        }
        return page.map(circulationMapper::toDocumentResponse);
    }

    /**
     * 文書詳細を取得する。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param documentId 文書ID
     * @return 文書レスポンス
     */
    public DocumentResponse getDocument(String scopeType, Long scopeId, Long documentId) {
        CirculationDocumentEntity entity = findDocumentOrThrow(scopeType, scopeId, documentId);
        return circulationMapper.toDocumentResponse(entity);
    }

    /**
     * 文書を作成する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    作成者ID
     * @param request   作成リクエスト
     * @return 作成された文書レスポンス
     */
    @Transactional
    public DocumentResponse createDocument(String scopeType, Long scopeId, Long userId,
                                           CreateDocumentRequest request) {
        CirculationDocumentEntity entity = CirculationDocumentEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .createdBy(userId)
                .title(request.getTitle())
                .body(request.getBody())
                .circulationMode(request.getCirculationMode() != null
                        ? CirculationMode.valueOf(request.getCirculationMode())
                        : CirculationMode.SIMULTANEOUS)
                .priority(request.getPriority() != null
                        ? CirculationPriority.valueOf(request.getPriority())
                        : CirculationPriority.NORMAL)
                .dueDate(request.getDueDate())
                .reminderEnabled(request.getReminderEnabled() != null ? request.getReminderEnabled() : false)
                .reminderIntervalHours(request.getReminderIntervalHours() != null
                        ? request.getReminderIntervalHours() : (short) 24)
                .stampDisplayStyle(request.getStampDisplayStyle() != null
                        ? StampDisplayStyle.valueOf(request.getStampDisplayStyle())
                        : StampDisplayStyle.STANDARD)
                .build();

        CirculationDocumentEntity saved = documentRepository.save(entity);

        addRecipientsInternal(saved, request.getRecipients());
        saved.updateRecipientCount(request.getRecipients().size());
        saved = documentRepository.save(saved);

        log.info("回覧文書作成: scopeType={}, scopeId={}, documentId={}", scopeType, scopeId, saved.getId());
        return circulationMapper.toDocumentResponse(saved);
    }

    /**
     * 文書を更新する。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param documentId 文書ID
     * @param request    更新リクエスト
     * @return 更新された文書レスポンス
     */
    @Transactional
    public DocumentResponse updateDocument(String scopeType, Long scopeId, Long documentId,
                                           UpdateDocumentRequest request) {
        CirculationDocumentEntity entity = findDocumentOrThrow(scopeType, scopeId, documentId);

        if (!entity.isEditable()) {
            throw new BusinessException(CirculationErrorCode.INVALID_DOCUMENT_STATUS);
        }

        if (request.getTitle() != null || request.getBody() != null) {
            entity.updateContent(
                    request.getTitle() != null ? request.getTitle() : entity.getTitle(),
                    request.getBody() != null ? request.getBody() : entity.getBody());
        }

        entity.updateSettings(
                request.getPriority() != null
                        ? CirculationPriority.valueOf(request.getPriority()) : entity.getPriority(),
                request.getDueDate() != null ? request.getDueDate() : entity.getDueDate(),
                request.getReminderEnabled() != null ? request.getReminderEnabled() : entity.getReminderEnabled(),
                request.getReminderIntervalHours() != null
                        ? request.getReminderIntervalHours() : entity.getReminderIntervalHours(),
                request.getStampDisplayStyle() != null
                        ? StampDisplayStyle.valueOf(request.getStampDisplayStyle()) : entity.getStampDisplayStyle());

        CirculationDocumentEntity saved = documentRepository.save(entity);
        log.info("回覧文書更新: documentId={}", documentId);
        return circulationMapper.toDocumentResponse(saved);
    }

    /**
     * 文書を公開する。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param documentId 文書ID
     * @return 更新された文書レスポンス
     */
    @Transactional
    public DocumentResponse activateDocument(String scopeType, Long scopeId, Long documentId) {
        CirculationDocumentEntity entity = findDocumentOrThrow(scopeType, scopeId, documentId);

        if (!entity.isEditable()) {
            throw new BusinessException(CirculationErrorCode.INVALID_DOCUMENT_STATUS);
        }

        long recipientCount = recipientRepository.countByDocumentId(documentId);
        if (recipientCount == 0) {
            throw new BusinessException(CirculationErrorCode.EMPTY_RECIPIENTS);
        }

        entity.activate();

        if (entity.getCirculationMode() == CirculationMode.SEQUENTIAL) {
            entity = entity.toBuilder()
                    .sequentialCount((int) recipientCount)
                    .build();
        }

        CirculationDocumentEntity saved = documentRepository.save(entity);
        log.info("回覧文書公開: documentId={}", documentId);
        return circulationMapper.toDocumentResponse(saved);
    }

    /**
     * 文書をキャンセルする。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param documentId 文書ID
     * @return 更新された文書レスポンス
     */
    @Transactional
    public DocumentResponse cancelDocument(String scopeType, Long scopeId, Long documentId) {
        CirculationDocumentEntity entity = findDocumentOrThrow(scopeType, scopeId, documentId);
        entity.cancel();
        CirculationDocumentEntity saved = documentRepository.save(entity);
        log.info("回覧文書キャンセル: documentId={}", documentId);
        return circulationMapper.toDocumentResponse(saved);
    }

    /**
     * 文書を論理削除する。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param documentId 文書ID
     */
    @Transactional
    public void deleteDocument(String scopeType, Long scopeId, Long documentId) {
        CirculationDocumentEntity entity = findDocumentOrThrow(scopeType, scopeId, documentId);
        entity.softDelete();
        documentRepository.save(entity);
        log.info("回覧文書削除: documentId={}", documentId);
    }

    /**
     * 受信者一覧を取得する。
     *
     * @param documentId 文書ID
     * @return 受信者レスポンスリスト
     */
    public List<RecipientResponse> listRecipients(Long documentId) {
        List<CirculationRecipientEntity> recipients =
                recipientRepository.findByDocumentIdOrderBySortOrderAsc(documentId);
        return circulationMapper.toRecipientResponseList(recipients);
    }

    /**
     * 受信者を追加する。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param documentId 文書ID
     * @param request    追加リクエスト
     * @return 受信者レスポンスリスト
     */
    @Transactional
    public List<RecipientResponse> addRecipients(String scopeType, Long scopeId, Long documentId,
                                                 AddRecipientsRequest request) {
        CirculationDocumentEntity document = findDocumentOrThrow(scopeType, scopeId, documentId);

        addRecipientsInternal(document, request.getRecipients());

        long count = recipientRepository.countByDocumentId(documentId);
        document.updateRecipientCount((int) count);
        documentRepository.save(document);

        List<CirculationRecipientEntity> all =
                recipientRepository.findByDocumentIdOrderBySortOrderAsc(documentId);
        log.info("受信者追加: documentId={}, 追加数={}", documentId, request.getRecipients().size());
        return circulationMapper.toRecipientResponseList(all);
    }

    /**
     * 受信者を削除する。
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param documentId  文書ID
     * @param recipientId 受信者ID
     */
    @Transactional
    public void removeRecipient(String scopeType, Long scopeId, Long documentId, Long recipientId) {
        findDocumentOrThrow(scopeType, scopeId, documentId);

        CirculationRecipientEntity recipient = recipientRepository.findById(recipientId)
                .filter(r -> r.getDocumentId().equals(documentId))
                .orElseThrow(() -> new BusinessException(CirculationErrorCode.RECIPIENT_NOT_FOUND));

        recipientRepository.delete(recipient);

        CirculationDocumentEntity document = findDocumentOrThrow(scopeType, scopeId, documentId);
        long count = recipientRepository.countByDocumentId(documentId);
        document.updateRecipientCount((int) count);
        documentRepository.save(document);

        log.info("受信者削除: documentId={}, recipientId={}", documentId, recipientId);
    }

    /**
     * 添付ファイル一覧を取得する。
     *
     * @param documentId 文書ID
     * @return 添付ファイルレスポンスリスト
     */
    public List<AttachmentResponse> listAttachments(Long documentId) {
        List<CirculationAttachmentEntity> attachments =
                attachmentRepository.findByDocumentIdOrderByCreatedAtAsc(documentId);
        return circulationMapper.toAttachmentResponseList(attachments);
    }

    /**
     * 添付ファイルを追加する。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param documentId 文書ID
     * @param request    添付リクエスト
     * @return 添付ファイルレスポンス
     */
    @Transactional
    public AttachmentResponse addAttachment(String scopeType, Long scopeId, Long documentId,
                                            CreateAttachmentRequest request) {
        CirculationDocumentEntity document = findDocumentOrThrow(scopeType, scopeId, documentId);

        CirculationAttachmentEntity attachment = CirculationAttachmentEntity.builder()
                .documentId(documentId)
                .fileKey(request.getFileKey())
                .originalFilename(request.getOriginalFilename())
                .fileSize(request.getFileSize())
                .mimeType(request.getMimeType())
                .build();

        CirculationAttachmentEntity saved = attachmentRepository.save(attachment);
        document.incrementAttachmentCount();
        documentRepository.save(document);

        log.info("添付ファイル追加: documentId={}, attachmentId={}", documentId, saved.getId());
        return circulationMapper.toAttachmentResponse(saved);
    }

    /**
     * 添付ファイルを削除する。
     *
     * @param scopeType    スコープ種別
     * @param scopeId      スコープID
     * @param documentId   文書ID
     * @param attachmentId 添付ファイルID
     */
    @Transactional
    public void removeAttachment(String scopeType, Long scopeId, Long documentId, Long attachmentId) {
        CirculationDocumentEntity document = findDocumentOrThrow(scopeType, scopeId, documentId);

        CirculationAttachmentEntity attachment = attachmentRepository.findByIdAndDocumentId(attachmentId, documentId)
                .orElseThrow(() -> new BusinessException(CirculationErrorCode.ATTACHMENT_NOT_FOUND));

        attachmentRepository.delete(attachment);
        document.decrementAttachmentCount();
        documentRepository.save(document);

        log.info("添付ファイル削除: documentId={}, attachmentId={}", documentId, attachmentId);
    }

    /**
     * 自分が作成した文書をページング取得する。
     *
     * @param userId   ユーザーID
     * @param pageable ページング情報
     * @return 文書レスポンスのページ
     */
    public Page<DocumentResponse> listCreatedDocuments(Long userId, Pageable pageable) {
        Page<CirculationDocumentEntity> page =
                documentRepository.findByCreatedByOrderByCreatedAtDesc(userId, pageable);
        return page.map(circulationMapper::toDocumentResponse);
    }

    /**
     * 文書の統計情報を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 統計レスポンス
     */
    public DocumentStatsResponse getStats(String scopeType, Long scopeId) {
        long draft = documentRepository.countByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, CirculationStatus.DRAFT);
        long active = documentRepository.countByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, CirculationStatus.ACTIVE);
        long completed = documentRepository.countByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, CirculationStatus.COMPLETED);
        long cancelled = documentRepository.countByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, CirculationStatus.CANCELLED);
        long total = draft + active + completed + cancelled;

        return new DocumentStatsResponse(total, draft, active, completed, cancelled);
    }

    /**
     * 文書を取得する。存在しない場合は例外をスローする。
     */
    private CirculationDocumentEntity findDocumentOrThrow(String scopeType, Long scopeId, Long documentId) {
        return documentRepository.findByIdAndScopeTypeAndScopeId(documentId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(CirculationErrorCode.DOCUMENT_NOT_FOUND));
    }

    /**
     * 受信者を内部的に追加する。
     */
    private void addRecipientsInternal(CirculationDocumentEntity document, List<RecipientEntry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            RecipientEntry entry = entries.get(i);

            if (recipientRepository.existsByDocumentIdAndUserId(document.getId(), entry.getUserId())) {
                throw new BusinessException(CirculationErrorCode.DUPLICATE_RECIPIENT);
            }

            CirculationRecipientEntity recipient = CirculationRecipientEntity.builder()
                    .documentId(document.getId())
                    .userId(entry.getUserId())
                    .sortOrder(entry.getSortOrder() != null ? entry.getSortOrder() : i)
                    .build();

            recipientRepository.save(recipient);
        }
    }
}
