package com.mannschaft.app.corkboard.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.corkboard.CorkboardErrorCode;
import com.mannschaft.app.corkboard.CorkboardMapper;
import com.mannschaft.app.corkboard.dto.BatchPositionRequest;
import com.mannschaft.app.corkboard.dto.CorkboardCardResponse;
import com.mannschaft.app.corkboard.dto.CreateCardRequest;
import com.mannschaft.app.corkboard.dto.UpdateCardRequest;
import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import com.mannschaft.app.corkboard.repository.CorkboardCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * コルクボードカードサービス。カードのCRUD・位置更新・アーカイブを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CorkboardCardService {

    private static final int MAX_CARDS_PER_BOARD = 200;

    private final CorkboardCardRepository cardRepository;
    private final CorkboardService corkboardService;
    private final CorkboardMapper corkboardMapper;

    /**
     * カードを追加する。
     */
    @Transactional
    public CorkboardCardResponse createCard(Long boardId, Long userId, CreateCardRequest request) {
        // ボード存在確認
        corkboardService.findBoardOrThrow(boardId);

        long count = cardRepository.countByCorkboardId(boardId);
        if (count >= MAX_CARDS_PER_BOARD) {
            throw new BusinessException(CorkboardErrorCode.CARD_LIMIT_EXCEEDED);
        }

        CorkboardCardEntity entity = CorkboardCardEntity.builder()
                .corkboardId(boardId)
                .cardType(request.getCardType())
                .referenceType(request.getReferenceType())
                .referenceId(request.getReferenceId())
                .title(request.getTitle())
                .body(request.getBody())
                .url(request.getUrl())
                .colorLabel(request.getColorLabel() != null ? request.getColorLabel() : "NONE")
                .cardSize(request.getCardSize() != null ? request.getCardSize() : "MEDIUM")
                .positionX(request.getPositionX() != null ? request.getPositionX() : 0)
                .positionY(request.getPositionY() != null ? request.getPositionY() : 0)
                .zIndex(request.getZIndex() != null ? request.getZIndex() : 0)
                .userNote(request.getUserNote())
                .autoArchiveAt(request.getAutoArchiveAt())
                .createdBy(userId)
                .build();

        CorkboardCardEntity saved = cardRepository.save(entity);
        log.info("カード追加: boardId={}, cardId={}, type={}", boardId, saved.getId(), request.getCardType());
        return corkboardMapper.toCardResponse(saved);
    }

    /**
     * カードを更新する。
     */
    @Transactional
    public CorkboardCardResponse updateCard(Long boardId, Long cardId, UpdateCardRequest request) {
        CorkboardCardEntity card = findCardOrThrow(boardId, cardId);

        card.update(
                request.getTitle(),
                request.getBody(),
                request.getUrl(),
                request.getColorLabel() != null ? request.getColorLabel() : card.getColorLabel(),
                request.getCardSize() != null ? request.getCardSize() : card.getCardSize(),
                request.getPositionX() != null ? request.getPositionX() : card.getPositionX(),
                request.getPositionY() != null ? request.getPositionY() : card.getPositionY(),
                request.getZIndex() != null ? request.getZIndex() : card.getZIndex(),
                request.getUserNote(),
                request.getAutoArchiveAt()
        );

        CorkboardCardEntity saved = cardRepository.save(card);
        log.info("カード更新: cardId={}", cardId);
        return corkboardMapper.toCardResponse(saved);
    }

    /**
     * カードを削除する（論理削除）。
     */
    @Transactional
    public void deleteCard(Long boardId, Long cardId) {
        CorkboardCardEntity card = findCardOrThrow(boardId, cardId);
        card.softDelete();
        cardRepository.save(card);
        log.info("カード削除: cardId={}", cardId);
    }

    /**
     * カードのアーカイブ状態を切り替える。
     */
    @Transactional
    public CorkboardCardResponse archiveCard(Long boardId, Long cardId, boolean archived) {
        CorkboardCardEntity card = findCardOrThrow(boardId, cardId);
        card.archive(archived);
        CorkboardCardEntity saved = cardRepository.save(card);
        log.info("カードアーカイブ: cardId={}, archived={}", cardId, archived);
        return corkboardMapper.toCardResponse(saved);
    }

    /**
     * カードの位置を一括更新する。
     */
    @Transactional
    public List<CorkboardCardResponse> batchUpdatePositions(Long boardId, BatchPositionRequest request) {
        // ボード存在確認
        corkboardService.findBoardOrThrow(boardId);

        List<CorkboardCardEntity> updatedCards = request.getPositions().stream()
                .map(pos -> {
                    CorkboardCardEntity card = findCardOrThrow(boardId, pos.getCardId());
                    card.updatePosition(pos.getPositionX(), pos.getPositionY(), pos.getZIndex());
                    return cardRepository.save(card);
                })
                .toList();

        log.info("カード一括位置更新: boardId={}, count={}", boardId, updatedCards.size());
        return corkboardMapper.toCardResponseList(updatedCards);
    }

    private CorkboardCardEntity findCardOrThrow(Long boardId, Long cardId) {
        return cardRepository.findByIdAndCorkboardId(cardId, boardId)
                .orElseThrow(() -> new BusinessException(CorkboardErrorCode.CARD_NOT_FOUND));
    }
}
