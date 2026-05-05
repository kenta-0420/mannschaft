package com.mannschaft.app.corkboard.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.corkboard.CorkboardErrorCode;
import com.mannschaft.app.corkboard.CorkboardMapper;
import com.mannschaft.app.corkboard.dto.CorkboardCardResponse;
import com.mannschaft.app.corkboard.dto.CorkboardGroupResponse;
import com.mannschaft.app.corkboard.dto.CreateGroupRequest;
import com.mannschaft.app.corkboard.dto.UpdateGroupRequest;
import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import com.mannschaft.app.corkboard.entity.CorkboardCardGroupEntity;
import com.mannschaft.app.corkboard.entity.CorkboardEntity;
import com.mannschaft.app.corkboard.entity.CorkboardGroupEntity;
import com.mannschaft.app.corkboard.event.CorkboardEvent;
import com.mannschaft.app.corkboard.repository.CorkboardCardGroupRepository;
import com.mannschaft.app.corkboard.repository.CorkboardCardRepository;
import com.mannschaft.app.corkboard.repository.CorkboardGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * コルクボードセクションサービス。セクションのCRUDとカード紐付けを担当する。
 *
 * <p>F09.8 Phase A 統合点:</p>
 * <ul>
 *   <li>A-2 edit_policy: {@link CorkboardPermissionService#checkEditPermission} で権限ガード</li>
 *   <li>A-3 WebSocket: 共有ボードのみ {@link CorkboardEvent} を発行</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CorkboardGroupService {

    private static final String SCOPE_PERSONAL = "PERSONAL";

    private final CorkboardGroupRepository groupRepository;
    private final CorkboardCardRepository cardRepository;
    private final CorkboardCardGroupRepository cardGroupRepository;
    private final CorkboardService corkboardService;
    private final CorkboardMapper corkboardMapper;
    private final CorkboardPermissionService permissionService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * セクションを作成する。
     */
    @Transactional
    public CorkboardGroupResponse createGroup(Long boardId, Long userId, CreateGroupRequest request) {
        CorkboardEntity board = corkboardService.findBoardOrThrow(boardId);
        permissionService.checkEditPermission(board, userId);

        CorkboardGroupEntity entity = CorkboardGroupEntity.builder()
                .corkboardId(boardId)
                .name(request.getName())
                .isCollapsed(request.getIsCollapsed() != null ? request.getIsCollapsed() : false)
                .positionX(request.getPositionX() != null ? request.getPositionX() : 0)
                .positionY(request.getPositionY() != null ? request.getPositionY() : 0)
                .width(request.getWidth() != null ? request.getWidth() : 400)
                .height(request.getHeight() != null ? request.getHeight() : 300)
                .displayOrder(request.getDisplayOrder() != null ? request.getDisplayOrder() : (short) 0)
                .build();

        CorkboardGroupEntity saved = groupRepository.save(entity);
        log.info("セクション作成: boardId={}, groupId={}", boardId, saved.getId());

        CorkboardGroupResponse response = corkboardMapper.toGroupResponse(saved);
        publishIfShared(board, CorkboardEvent.sectionCreated(boardId, response));

        return response;
    }

    /**
     * セクションを更新する。
     */
    @Transactional
    public CorkboardGroupResponse updateGroup(Long boardId, Long userId, Long groupId, UpdateGroupRequest request) {
        CorkboardEntity board = corkboardService.findBoardOrThrow(boardId);
        permissionService.checkEditPermission(board, userId);

        CorkboardGroupEntity group = findGroupOrThrow(boardId, groupId);

        group.update(
                request.getName(),
                request.getIsCollapsed() != null ? request.getIsCollapsed() : group.getIsCollapsed(),
                request.getPositionX() != null ? request.getPositionX() : group.getPositionX(),
                request.getPositionY() != null ? request.getPositionY() : group.getPositionY(),
                request.getWidth() != null ? request.getWidth() : group.getWidth(),
                request.getHeight() != null ? request.getHeight() : group.getHeight(),
                request.getDisplayOrder() != null ? request.getDisplayOrder() : group.getDisplayOrder()
        );

        CorkboardGroupEntity saved = groupRepository.save(group);
        log.info("セクション更新: groupId={}", groupId);

        CorkboardGroupResponse response = corkboardMapper.toGroupResponse(saved);
        publishIfShared(board, CorkboardEvent.sectionUpdated(boardId, response));

        return response;
    }

    /**
     * セクションを削除する。
     */
    @Transactional
    public void deleteGroup(Long boardId, Long userId, Long groupId) {
        CorkboardEntity board = corkboardService.findBoardOrThrow(boardId);
        permissionService.checkEditPermission(board, userId);

        CorkboardGroupEntity group = findGroupOrThrow(boardId, groupId);
        cardGroupRepository.deleteByGroupId(groupId);
        groupRepository.delete(group);
        log.info("セクション削除: groupId={}", groupId);

        // 件B: 削除は sectionId のみで OK（フロントは filter で削除できる）
        publishIfShared(board, CorkboardEvent.sectionDeleted(boardId, groupId));
    }

    /**
     * カードをセクションに追加する。
     *
     * <p>F09.8 積み残し件1 (V9.097): 中間テーブル {@code corkboard_card_groups} の INSERT に加え、
     * primary section として {@code corkboard_cards.section_id} も同時に更新する。
     * これによりフロントはリロード後も `card.sectionId` で紐付け状態を取得できる。</p>
     */
    @Transactional
    public void addCardToGroup(Long boardId, Long userId, Long groupId, Long cardId) {
        CorkboardEntity board = corkboardService.findBoardOrThrow(boardId);
        permissionService.checkEditPermission(board, userId);

        findGroupOrThrow(boardId, groupId);
        CorkboardCardEntity card = cardRepository.findByIdAndCorkboardId(cardId, boardId)
                .orElseThrow(() -> new BusinessException(CorkboardErrorCode.CARD_NOT_FOUND));

        if (cardGroupRepository.findByCardIdAndGroupId(cardId, groupId).isPresent()) {
            throw new BusinessException(CorkboardErrorCode.CARD_ALREADY_IN_GROUP);
        }

        CorkboardCardGroupEntity relation = CorkboardCardGroupEntity.builder()
                .cardId(cardId)
                .groupId(groupId)
                .build();

        cardGroupRepository.save(relation);

        // 積み残し件1: primary section の正規列を更新
        card.assignSection(groupId);
        CorkboardCardEntity savedCard = cardRepository.save(card);

        log.info("カードをセクションに追加: cardId={}, groupId={}", cardId, groupId);

        // 件B: 共有ボードのみ DTO を組み立てて配信（個人ボードでは無駄なマッピングを避ける）
        if (isShared(board)) {
            CorkboardCardResponse cardResponse = corkboardMapper.toCardResponse(savedCard);
            eventPublisher.publishEvent(CorkboardEvent.cardSectionChanged(boardId, cardResponse, groupId));
        }
    }

    /**
     * カードをセクションから削除する。
     *
     * <p>F09.8 積み残し件1 (V9.097): 中間テーブル削除に加え、現在の primary section が指定 groupId と
     * 一致する場合のみ {@code corkboard_cards.section_id} を {@code null} に戻す。</p>
     */
    @Transactional
    public void removeCardFromGroup(Long boardId, Long userId, Long groupId, Long cardId) {
        CorkboardEntity board = corkboardService.findBoardOrThrow(boardId);
        permissionService.checkEditPermission(board, userId);

        findGroupOrThrow(boardId, groupId);

        CorkboardCardGroupEntity relation = cardGroupRepository.findByCardIdAndGroupId(cardId, groupId)
                .orElseThrow(() -> new BusinessException(CorkboardErrorCode.CARD_NOT_IN_GROUP));

        cardGroupRepository.delete(relation);

        // 積み残し件1: primary section が指定 group と一致する場合のみクリア
        // 件B: 配信用 DTO は最新状態（クリア後があればそれ、無ければ既存値）で組み立てる
        CorkboardCardEntity latestCard = cardRepository.findByIdAndCorkboardId(cardId, boardId)
                .map(card -> {
                    if (groupId.equals(card.getSectionId())) {
                        card.assignSection(null);
                        return cardRepository.save(card);
                    }
                    return card;
                })
                .orElse(null);

        log.info("カードをセクションから削除: cardId={}, groupId={}", cardId, groupId);

        // 件B: 共有ボードのみ配信。カード DTO（sectionId 反映済み）を含めることで
        // フロントは局所更新が可能になる。カードが見つからない異常系は cardId のみで
        // 配信し、フロントは null フォールバックで load() に倒す。
        if (isShared(board)) {
            if (latestCard != null) {
                CorkboardCardResponse cardResponse = corkboardMapper.toCardResponse(latestCard);
                eventPublisher.publishEvent(CorkboardEvent.cardSectionChanged(boardId, cardResponse, null));
            } else {
                eventPublisher.publishEvent(CorkboardEvent.cardSection(boardId, cardId, null));
            }
        }
    }

    private CorkboardGroupEntity findGroupOrThrow(Long boardId, Long groupId) {
        return groupRepository.findByIdAndCorkboardId(groupId, boardId)
                .orElseThrow(() -> new BusinessException(CorkboardErrorCode.GROUP_NOT_FOUND));
    }

    private void publishIfShared(CorkboardEntity board, CorkboardEvent event) {
        if (isShared(board)) {
            eventPublisher.publishEvent(event);
        }
    }

    private boolean isShared(CorkboardEntity board) {
        return board != null && !SCOPE_PERSONAL.equals(board.getScopeType());
    }
}
