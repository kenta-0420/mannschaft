package com.mannschaft.app.corkboard.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.corkboard.CorkboardErrorCode;
import com.mannschaft.app.corkboard.CorkboardMapper;
import com.mannschaft.app.corkboard.dto.BatchPositionRequest;
import com.mannschaft.app.corkboard.dto.CorkboardCardResponse;
import com.mannschaft.app.corkboard.dto.CreateCardRequest;
import com.mannschaft.app.corkboard.dto.UpdateCardRequest;
import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import com.mannschaft.app.corkboard.entity.CorkboardEntity;
import com.mannschaft.app.corkboard.event.CorkboardEvent;
import com.mannschaft.app.corkboard.repository.CorkboardCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * コルクボードカードサービス。カードのCRUD・位置更新・アーカイブを担当する。
 *
 * <p>F09.8 Phase A 統合点:</p>
 * <ul>
 *   <li>A-1 OGP: URL カード作成時に {@link OgpFetchService#fetchAndUpdate} を非同期呼び出し</li>
 *   <li>A-2 edit_policy: {@link CorkboardPermissionService#checkEditPermission} で権限ガード</li>
 *   <li>A-3 WebSocket: 共有ボードのみ {@link CorkboardEvent} を発行</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CorkboardCardService {

    private static final int MAX_CARDS_PER_BOARD = 200;
    private static final String CARD_TYPE_URL = "URL";
    private static final String SCOPE_PERSONAL = "PERSONAL";

    private final CorkboardCardRepository cardRepository;
    private final CorkboardService corkboardService;
    private final CorkboardMapper corkboardMapper;
    private final CorkboardPermissionService permissionService;
    private final OgpFetchService ogpFetchService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * カードを追加する。
     */
    @Transactional
    public CorkboardCardResponse createCard(Long boardId, Long userId, CreateCardRequest request) {
        // ボード存在確認 + 権限チェック
        CorkboardEntity board = corkboardService.findBoardOrThrow(boardId);
        permissionService.checkEditPermission(board, userId);

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

        // A-1: URL カードは OGP を非同期取得（コミット後に実行する）
        if (CARD_TYPE_URL.equals(saved.getCardType()) && saved.getUrl() != null) {
            scheduleOgpFetchAfterCommit(saved.getId(), saved.getUrl());
        }

        // A-3: 共有ボードのみイベント発行
        publishIfShared(board, CorkboardEvent.card(boardId, CorkboardEvent.Type.CARD_CREATED, saved.getId()));

        return corkboardMapper.toCardResponse(saved);
    }

    /**
     * カードを更新する。
     */
    @Transactional
    public CorkboardCardResponse updateCard(Long boardId, Long userId, Long cardId, UpdateCardRequest request) {
        CorkboardEntity board = corkboardService.findBoardOrThrow(boardId);
        permissionService.checkEditPermission(board, userId);

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

        publishIfShared(board, CorkboardEvent.card(boardId, CorkboardEvent.Type.CARD_UPDATED, cardId));

        return corkboardMapper.toCardResponse(saved);
    }

    /**
     * カードを削除する（論理削除）。
     */
    @Transactional
    public void deleteCard(Long boardId, Long userId, Long cardId) {
        CorkboardEntity board = corkboardService.findBoardOrThrow(boardId);
        permissionService.checkEditPermission(board, userId);

        CorkboardCardEntity card = findCardOrThrow(boardId, cardId);
        card.softDelete();
        cardRepository.save(card);
        log.info("カード削除: cardId={}", cardId);

        publishIfShared(board, CorkboardEvent.card(boardId, CorkboardEvent.Type.CARD_DELETED, cardId));
    }

    /**
     * カードのアーカイブ状態を切り替える。
     */
    @Transactional
    public CorkboardCardResponse archiveCard(Long boardId, Long userId, Long cardId, boolean archived) {
        CorkboardEntity board = corkboardService.findBoardOrThrow(boardId);
        permissionService.checkEditPermission(board, userId);

        CorkboardCardEntity card = findCardOrThrow(boardId, cardId);
        card.archive(archived);
        CorkboardCardEntity saved = cardRepository.save(card);
        log.info("カードアーカイブ: cardId={}, archived={}", cardId, archived);

        publishIfShared(board, CorkboardEvent.card(boardId, CorkboardEvent.Type.CARD_ARCHIVED, cardId));

        return corkboardMapper.toCardResponse(saved);
    }

    /**
     * カードの位置を一括更新する。
     */
    @Transactional
    public List<CorkboardCardResponse> batchUpdatePositions(Long boardId, Long userId, BatchPositionRequest request) {
        CorkboardEntity board = corkboardService.findBoardOrThrow(boardId);
        permissionService.checkEditPermission(board, userId);

        List<CorkboardCardEntity> updatedCards = request.getPositions().stream()
                .map(pos -> {
                    CorkboardCardEntity card = findCardOrThrow(boardId, pos.getCardId());
                    card.updatePosition(pos.getPositionX(), pos.getPositionY(), pos.getZIndex());
                    return cardRepository.save(card);
                })
                .toList();

        log.info("カード一括位置更新: boardId={}, count={}", boardId, updatedCards.size());

        // 一括移動: 個別の cardId を含めて配信（フロント側で順次反映）
        if (isShared(board)) {
            for (CorkboardCardEntity card : updatedCards) {
                eventPublisher.publishEvent(
                        CorkboardEvent.card(boardId, CorkboardEvent.Type.CARD_MOVED, card.getId()));
            }
        }

        return corkboardMapper.toCardResponseList(updatedCards);
    }

    private CorkboardCardEntity findCardOrThrow(Long boardId, Long cardId) {
        return cardRepository.findByIdAndCorkboardId(cardId, boardId)
                .orElseThrow(() -> new BusinessException(CorkboardErrorCode.CARD_NOT_FOUND));
    }

    /**
     * 共有ボード（TEAM/ORGANIZATION）の場合のみイベントを発行する。
     */
    private void publishIfShared(CorkboardEntity board, CorkboardEvent event) {
        if (isShared(board)) {
            eventPublisher.publishEvent(event);
        }
    }

    private boolean isShared(CorkboardEntity board) {
        return board != null && !SCOPE_PERSONAL.equals(board.getScopeType());
    }

    /**
     * トランザクションコミット後に OGP 非同期取得を呼び出す。
     * トランザクション中の OGP 取得を避け、保存済みカードに対してのみ更新を試みる。
     */
    private void scheduleOgpFetchAfterCommit(Long cardId, String url) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    ogpFetchService.fetchAndUpdate(cardId, url);
                }
            });
        } else {
            ogpFetchService.fetchAndUpdate(cardId, url);
        }
    }
}
