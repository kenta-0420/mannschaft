package com.mannschaft.app.circulation.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.circulation.CirculationErrorCode;
import com.mannschaft.app.circulation.CirculationMapper;
import com.mannschaft.app.circulation.CirculationMode;
import com.mannschaft.app.circulation.RecipientStatus;
import com.mannschaft.app.circulation.dto.AdminSkipRecipientRequest;
import com.mannschaft.app.circulation.dto.RecipientResponse;
import com.mannschaft.app.circulation.dto.StampCorrectionRequest;
import com.mannschaft.app.circulation.dto.StampDelegationRequest;
import com.mannschaft.app.circulation.dto.StampDelegationResponse;
import com.mannschaft.app.circulation.dto.StampRequest;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import com.mannschaft.app.circulation.entity.CirculationStampCorrectionLogEntity;
import com.mannschaft.app.circulation.entity.CirculationStampDelegationEntity;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.circulation.repository.CirculationRecipientRepository;
import com.mannschaft.app.circulation.repository.CirculationStampCorrectionLogRepository;
import com.mannschaft.app.circulation.repository.CirculationStampDelegationRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 押印サービス。回覧文書への押印・スキップ・拒否を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CirculationStampService {

    /** F05.2 Phase 11 第三陣 3-B: 押印訂正の許容期間（押印後 24h）。 */
    private static final Duration CORRECTION_WINDOW = Duration.ofHours(24);

    private final CirculationDocumentRepository documentRepository;
    private final CirculationRecipientRepository recipientRepository;
    private final CirculationMapper circulationMapper;
    private final ProxyInputContext proxyInputContext;
    private final ProxyInputRecordRepository proxyInputRecordRepository;

    /** F05.2 Phase 11 第三陣 3-B: 押印訂正履歴。 */
    @Autowired(required = false)
    private CirculationStampCorrectionLogRepository correctionLogRepository;

    /** F05.2 Phase 11 第三陣 3-B: 押印委任。 */
    @Autowired(required = false)
    private CirculationStampDelegationRepository delegationRepository;

    /** F05.2 Phase 11 第三陣 3-B: 監査ログ。 */
    @Autowired(required = false)
    private AuditLogService auditLogService;

    /**
     * 押印する。
     *
     * @param documentId 文書ID
     * @param userId     ユーザーID
     * @param request    押印リクエスト
     * @return 受信者レスポンス
     */
    @Transactional
    public RecipientResponse stamp(Long documentId, Long userId, StampRequest request) {
        CirculationDocumentEntity document = findDocumentOrThrow(documentId);

        if (!document.isActive()) {
            throw new BusinessException(CirculationErrorCode.INVALID_DOCUMENT_STATUS);
        }

        CirculationRecipientEntity recipient = findRecipientOrThrow(documentId, userId);

        if (!recipient.isStampable()) {
            throw new BusinessException(CirculationErrorCode.INVALID_RECIPIENT_STATUS);
        }

        validateSequentialOrder(document, recipient);

        recipient.stamp(request.getSealId(), request.getSealVariant(),
                request.getTiltAngle(), request.getIsFlipped());
        CirculationRecipientEntity savedRecipient = recipientRepository.save(recipient);

        // 代理確認の場合: proxy_input_records を作成し、is_proxy_confirmed フラグをセット
        if (proxyInputContext.isProxy()) {
            ProxyInputRecordEntity proxyRecord = buildAndSaveStampProxyRecord(
                    "CIRCULATION_STAMP", savedRecipient.getId());
            savedRecipient = recipientRepository.save(savedRecipient.toBuilder()
                    .isProxyConfirmed(true)
                    .proxyInputRecordId(proxyRecord.getId())
                    .build());
        }

        document.incrementStampedCount();
        if (document.isAllStamped()) {
            document.complete();
        }
        documentRepository.save(document);

        log.info("押印完了: documentId={}, userId={}", documentId, userId);
        return circulationMapper.toRecipientResponse(savedRecipient);
    }

    /**
     * スキップする。
     *
     * @param documentId 文書ID
     * @param userId     ユーザーID
     * @return 受信者レスポンス
     */
    @Transactional
    public RecipientResponse skip(Long documentId, Long userId) {
        CirculationDocumentEntity document = findDocumentOrThrow(documentId);

        if (!document.isActive()) {
            throw new BusinessException(CirculationErrorCode.INVALID_DOCUMENT_STATUS);
        }

        CirculationRecipientEntity recipient = findRecipientOrThrow(documentId, userId);

        if (!recipient.isStampable()) {
            throw new BusinessException(CirculationErrorCode.INVALID_RECIPIENT_STATUS);
        }

        recipient.skip();
        CirculationRecipientEntity saved = recipientRepository.save(recipient);

        log.info("スキップ: documentId={}, userId={}", documentId, userId);
        return circulationMapper.toRecipientResponse(saved);
    }

    /**
     * 拒否する。
     *
     * @param documentId 文書ID
     * @param userId     ユーザーID
     * @return 受信者レスポンス
     */
    @Transactional
    public RecipientResponse reject(Long documentId, Long userId) {
        CirculationDocumentEntity document = findDocumentOrThrow(documentId);

        if (!document.isActive()) {
            throw new BusinessException(CirculationErrorCode.INVALID_DOCUMENT_STATUS);
        }

        CirculationRecipientEntity recipient = findRecipientOrThrow(documentId, userId);

        if (!recipient.isStampable()) {
            throw new BusinessException(CirculationErrorCode.INVALID_RECIPIENT_STATUS);
        }

        recipient.reject();
        CirculationRecipientEntity saved = recipientRepository.save(recipient);

        log.info("拒否: documentId={}, userId={}", documentId, userId);
        return circulationMapper.toRecipientResponse(saved);
    }

    /**
     * 押印を訂正する（受信者本人）。
     *
     * <p>F05.2 Phase 11 第三陣 3-B: 押印済みの受信者が、自分の押印を訂正する。
     * 訂正後 24 時間以内に限り可能。訂正前のスナップショットを
     * {@link CirculationStampCorrectionLogEntity} に保存してから受信者を
     * PENDING に戻す。文書の {@code stampedCount} もデクリメントする。</p>
     *
     * @param documentId 文書ID
     * @param userId     ユーザーID（押印者本人）
     * @param request    訂正リクエスト
     * @return 受信者レスポンス（status=PENDING）
     */
    @Transactional
    public RecipientResponse correctStamp(Long documentId, Long userId, StampCorrectionRequest request) {
        CirculationDocumentEntity document = findDocumentOrThrow(documentId);

        // 文書が ACTIVE / COMPLETED のいずれかでないと訂正できない
        if (!(document.isActive() || document.getStatus() == com.mannschaft.app.circulation.CirculationStatus.COMPLETED)) {
            throw new BusinessException(CirculationErrorCode.INVALID_DOCUMENT_STATUS);
        }

        CirculationRecipientEntity recipient = findRecipientOrThrow(documentId, userId);

        if (recipient.getStatus() != RecipientStatus.STAMPED) {
            throw new BusinessException(CirculationErrorCode.NOT_STAMPED_CANNOT_CORRECT);
        }

        // 訂正可能期間チェック（押印後 24h）
        if (recipient.getStampedAt() == null
                || recipient.getStampedAt().plus(CORRECTION_WINDOW).isBefore(LocalDateTime.now())) {
            throw new BusinessException(CirculationErrorCode.CORRECTION_WINDOW_EXPIRED);
        }

        // 訂正前スナップショット保存
        if (correctionLogRepository != null) {
            CirculationStampCorrectionLogEntity logEntry = CirculationStampCorrectionLogEntity.builder()
                    .recipientId(recipient.getId())
                    .documentId(documentId)
                    .correctedBy(userId)
                    .originalSealId(recipient.getSealId())
                    .originalSealVariant(recipient.getSealVariant())
                    .originalTiltAngle(recipient.getTiltAngle())
                    .originalIsFlipped(recipient.getIsFlipped())
                    .reason(request != null ? request.reason() : null)
                    .build();
            correctionLogRepository.save(logEntry);
        }

        // 受信者の押印を取り消す（PENDING に戻す）
        recipient.correctStamp();
        CirculationRecipientEntity saved = recipientRepository.save(recipient);

        // 文書の押印数をデクリメント
        if (document.getStampedCount() > 0) {
            document = document.toBuilder()
                    .stampedCount(document.getStampedCount() - 1)
                    .build();
            // COMPLETED から ACTIVE に戻す（再押印待ち）
            if (document.getStatus() == com.mannschaft.app.circulation.CirculationStatus.COMPLETED) {
                document.activate();
            }
            documentRepository.save(document);
        }

        recordAudit(AuditEventType.CIRCULATION_STAMP_CORRECTED, userId, documentId,
                "{\"recipientId\":" + recipient.getId()
                        + ",\"reason\":\"" + escape(request != null ? request.reason() : null) + "\"}");

        log.info("押印訂正: documentId={}, userId={}", documentId, userId);
        return circulationMapper.toRecipientResponse(saved);
    }

    /**
     * 押印を委任する。
     *
     * <p>F05.2 Phase 11 第三陣 3-B: 受信者本人 (delegator) が別ユーザー (delegatee)
     * に押印を委任する。同一文書内では一委任者につき 1 件のみ。</p>
     *
     * @param documentId      文書ID
     * @param delegatorUserId 委任者ID（本人）
     * @param request         委任リクエスト
     * @return 委任レスポンス
     */
    @Transactional
    public StampDelegationResponse delegateStamp(Long documentId, Long delegatorUserId,
                                                 StampDelegationRequest request) {
        CirculationDocumentEntity document = findDocumentOrThrow(documentId);

        if (!document.isActive()) {
            throw new BusinessException(CirculationErrorCode.INVALID_DOCUMENT_STATUS);
        }

        // 委任者が受信者として登録されているか
        CirculationRecipientEntity recipient = findRecipientOrThrow(documentId, delegatorUserId);
        if (recipient.getStatus() != RecipientStatus.PENDING) {
            throw new BusinessException(CirculationErrorCode.INVALID_RECIPIENT_STATUS);
        }

        // 自分自身への委任は禁止
        if (delegatorUserId.equals(request.delegateeUserId())) {
            throw new BusinessException(CirculationErrorCode.SELF_DELEGATION_NOT_ALLOWED);
        }

        if (delegationRepository == null) {
            // 通常は @Autowired される。null は単体テスト用の安全弁
            throw new IllegalStateException("delegationRepository is not wired");
        }

        // 重複登録チェック
        delegationRepository.findByDocumentIdAndDelegatorUserId(documentId, delegatorUserId)
                .ifPresent(existing -> {
                    if (existing.isActive()) {
                        throw new BusinessException(CirculationErrorCode.DELEGATION_ALREADY_EXISTS);
                    }
                });

        CirculationStampDelegationEntity entity = CirculationStampDelegationEntity.builder()
                .documentId(documentId)
                .delegatorUserId(delegatorUserId)
                .delegateeUserId(request.delegateeUserId())
                .reason(request.reason())
                .build();
        CirculationStampDelegationEntity saved = delegationRepository.save(entity);

        recordAudit(AuditEventType.CIRCULATION_STAMP_DELEGATED, delegatorUserId, documentId,
                "{\"delegationId\":\"" + saved.getId()
                        + "\",\"delegateeUserId\":" + request.delegateeUserId()
                        + ",\"reason\":\"" + escape(request.reason()) + "\"}");

        log.info("押印委任: documentId={}, delegator={}, delegatee={}",
                documentId, delegatorUserId, request.delegateeUserId());

        return new StampDelegationResponse(
                saved.getId(),
                saved.getDocumentId(),
                saved.getDelegatorUserId(),
                saved.getDelegateeUserId(),
                saved.getReason(),
                saved.getStatus().name(),
                saved.getCreatedAt());
    }

    /**
     * ADMIN による受信者強制スキップ。
     *
     * <p>F05.2 Phase 11 第三陣 3-B: ADMIN が特定受信者（退職者・休職者など）を
     * SKIPPED に強制遷移させる。ADMIN 権限の判定は Controller 層 ({@code @PreAuthorize})
     * に委ね、本メソッドは {@code adminUserId} の妥当性（不在の場合は呼び出し側エラー）
     * のみ前提とする。</p>
     *
     * @param documentId    文書ID
     * @param targetUserId  対象受信者の user_id
     * @param adminUserId   操作実行 ADMIN の user_id
     * @param request       強制スキップリクエスト（理由必須）
     * @return 受信者レスポンス
     */
    @Transactional
    public RecipientResponse adminSkipRecipient(Long documentId, Long targetUserId,
                                                Long adminUserId, AdminSkipRecipientRequest request) {
        CirculationDocumentEntity document = findDocumentOrThrow(documentId);

        if (!document.isActive()) {
            throw new BusinessException(CirculationErrorCode.INVALID_DOCUMENT_STATUS);
        }

        CirculationRecipientEntity recipient = findRecipientOrThrow(documentId, targetUserId);

        if (!recipient.isStampable()) {
            throw new BusinessException(CirculationErrorCode.INVALID_RECIPIENT_STATUS);
        }

        recipient.adminSkip(adminUserId, request.reason());
        CirculationRecipientEntity saved = recipientRepository.save(recipient);

        // 文書の押印完了判定（スキップにより残全員確定の場合は COMPLETED）
        long pending = recipientRepository.countByDocumentIdAndStatus(documentId, RecipientStatus.PENDING);
        if (pending == 0) {
            document.complete();
            documentRepository.save(document);
        }

        recordAudit(AuditEventType.CIRCULATION_RECIPIENT_SKIPPED, adminUserId, documentId,
                "{\"targetUserId\":" + targetUserId
                        + ",\"reason\":\"" + escape(request.reason()) + "\"}");

        log.info("ADMIN 強制スキップ: documentId={}, targetUserId={}, adminUserId={}, reason={}",
                documentId, targetUserId, adminUserId, request.reason());

        return circulationMapper.toRecipientResponse(saved);
    }

    /**
     * 監査ログを記録する（auditLogService が wired されている場合のみ）。
     */
    private void recordAudit(AuditEventType type, Long userId, Long documentId, String metadata) {
        if (auditLogService == null) {
            return;
        }
        String enrichedMetadata = metadata == null
                ? "{\"documentId\":" + documentId + "}"
                : metadata.replaceFirst("\\{", "{\"documentId\":" + documentId + ",");
        auditLogService.record(type.name(), userId, null, null, null,
                null, null, null, enrichedMetadata);
    }

    /** JSON 文字列の最小限のエスケープ。 */
    private String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 順次回覧の順序を検証する。
     */
    private void validateSequentialOrder(CirculationDocumentEntity document,
                                         CirculationRecipientEntity recipient) {
        if (document.getCirculationMode() != CirculationMode.SEQUENTIAL) {
            return;
        }

        List<CirculationRecipientEntity> recipients =
                recipientRepository.findByDocumentIdOrderBySortOrderAsc(document.getId());

        for (CirculationRecipientEntity r : recipients) {
            if (r.getId().equals(recipient.getId())) {
                break;
            }
            if (r.getStatus() == RecipientStatus.PENDING) {
                throw new BusinessException(CirculationErrorCode.SEQUENTIAL_ORDER_VIOLATION);
            }
        }
    }

    /**
     * 文書を取得する。存在しない場合は例外をスローする。
     */
    private CirculationDocumentEntity findDocumentOrThrow(Long documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException(CirculationErrorCode.DOCUMENT_NOT_FOUND));
    }

    /**
     * 受信者を取得する。存在しない場合は例外をスローする。
     */
    private CirculationRecipientEntity findRecipientOrThrow(Long documentId, Long userId) {
        return recipientRepository.findByDocumentIdAndUserId(documentId, userId)
                .orElseThrow(() -> new BusinessException(CirculationErrorCode.RECIPIENT_NOT_FOUND));
    }

    /**
     * 代理確認押印の記録を作成して保存する（冪等性チェックあり）。
     *
     * @param targetEntityType 対象エンティティ種別
     * @param targetEntityId   対象エンティティID
     * @return 保存済み代理入力記録エンティティ
     */
    private ProxyInputRecordEntity buildAndSaveStampProxyRecord(String targetEntityType, Long targetEntityId) {
        Long proxyUserId = SecurityUtils.getCurrentUserIdOrNull();
        // 冪等性チェック（紙運用での二重登録防止）
        return proxyInputRecordRepository.findByProxyInputConsentIdAndTargetEntityTypeAndTargetEntityId(
                proxyInputContext.getConsentId(), targetEntityType, targetEntityId)
                .orElseGet(() -> proxyInputRecordRepository.save(
                        ProxyInputRecordEntity.builder()
                                .proxyInputConsentId(proxyInputContext.getConsentId())
                                .subjectUserId(proxyInputContext.getSubjectUserId())
                                .proxyUserId(proxyUserId)
                                .featureScope("CIRCULAR")
                                .targetEntityType(targetEntityType)
                                .targetEntityId(targetEntityId)
                                .inputSource(ProxyInputRecordEntity.InputSource.valueOf(
                                        proxyInputContext.getInputSource()))
                                .originalStorageLocation(proxyInputContext.getOriginalStorageLocation())
                                .build()));
    }
}
