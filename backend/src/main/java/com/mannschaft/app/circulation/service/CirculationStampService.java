package com.mannschaft.app.circulation.service;

import com.mannschaft.app.circulation.CirculationErrorCode;
import com.mannschaft.app.circulation.CirculationMapper;
import com.mannschaft.app.circulation.CirculationMode;
import com.mannschaft.app.circulation.RecipientStatus;
import com.mannschaft.app.circulation.dto.RecipientResponse;
import com.mannschaft.app.circulation.dto.StampRequest;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.circulation.repository.CirculationRecipientRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 押印サービス。回覧文書への押印・スキップ・拒否を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CirculationStampService {

    private final CirculationDocumentRepository documentRepository;
    private final CirculationRecipientRepository recipientRepository;
    private final CirculationMapper circulationMapper;

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
        CirculationRecipientEntity saved = recipientRepository.save(recipient);

        document.incrementStampedCount();
        if (document.isAllStamped()) {
            document.complete();
        }
        documentRepository.save(document);

        log.info("押印完了: documentId={}, userId={}", documentId, userId);
        return circulationMapper.toRecipientResponse(saved);
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
}
