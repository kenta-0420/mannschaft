package com.mannschaft.app.corkboard.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.corkboard.CorkboardErrorCode;
import com.mannschaft.app.corkboard.CorkboardMapper;
import com.mannschaft.app.corkboard.dto.CorkboardDetailResponse;
import com.mannschaft.app.corkboard.dto.CorkboardResponse;
import com.mannschaft.app.corkboard.dto.CreateCorkboardRequest;
import com.mannschaft.app.corkboard.dto.UpdateCorkboardRequest;
import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import com.mannschaft.app.corkboard.entity.CorkboardEntity;
import com.mannschaft.app.corkboard.entity.CorkboardGroupEntity;
import com.mannschaft.app.corkboard.event.CorkboardEvent;
import com.mannschaft.app.corkboard.repository.CorkboardCardRepository;
import com.mannschaft.app.corkboard.repository.CorkboardGroupRepository;
import com.mannschaft.app.corkboard.repository.CorkboardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * コルクボードサービス。ボードのCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CorkboardService {

    private static final int MAX_BOARDS_PER_USER = 20;
    private static final int MAX_BOARDS_PER_SCOPE = 50;

    private final CorkboardRepository corkboardRepository;
    private final CorkboardCardRepository cardRepository;
    private final CorkboardGroupRepository groupRepository;
    private final CorkboardMapper corkboardMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 個人ボード一覧を取得する。
     */
    public List<CorkboardResponse> listPersonalBoards(Long userId) {
        List<CorkboardEntity> boards = corkboardRepository
                .findByOwnerIdAndScopeTypeOrderByCreatedAtDesc(userId, "PERSONAL");
        return corkboardMapper.toBoardResponseList(boards);
    }

    /**
     * スコープ別ボード一覧を取得する（チーム/組織）。
     */
    public List<CorkboardResponse> listScopedBoards(String scopeType, Long scopeId) {
        List<CorkboardEntity> boards = corkboardRepository
                .findByScopeTypeAndScopeIdOrderByCreatedAtDesc(scopeType, scopeId);
        return corkboardMapper.toBoardResponseList(boards);
    }

    /**
     * 個人ボードを作成する。
     */
    @Transactional
    public CorkboardResponse createPersonalBoard(Long userId, CreateCorkboardRequest request) {
        long count = corkboardRepository.countByOwnerId(userId);
        if (count >= MAX_BOARDS_PER_USER) {
            throw new BusinessException(CorkboardErrorCode.BOARD_LIMIT_EXCEEDED);
        }

        CorkboardEntity entity = CorkboardEntity.builder()
                .scopeType("PERSONAL")
                .ownerId(userId)
                .name(request.getName())
                .backgroundStyle(request.getBackgroundStyle() != null ? request.getBackgroundStyle() : "CORK")
                .editPolicy("ADMIN_ONLY")
                .isDefault(request.getIsDefault() != null ? request.getIsDefault() : false)
                .build();

        CorkboardEntity saved = corkboardRepository.save(entity);
        log.info("個人コルクボード作成: userId={}, boardId={}", userId, saved.getId());
        return corkboardMapper.toBoardResponse(saved);
    }

    /**
     * スコープ別ボードを作成する（チーム/組織）。
     */
    @Transactional
    public CorkboardResponse createScopedBoard(String scopeType, Long scopeId, CreateCorkboardRequest request) {
        long count = corkboardRepository.countByScopeTypeAndScopeId(scopeType, scopeId);
        if (count >= MAX_BOARDS_PER_SCOPE) {
            throw new BusinessException(CorkboardErrorCode.BOARD_LIMIT_EXCEEDED);
        }

        CorkboardEntity entity = CorkboardEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(request.getName())
                .backgroundStyle(request.getBackgroundStyle() != null ? request.getBackgroundStyle() : "CORK")
                .editPolicy(request.getEditPolicy() != null ? request.getEditPolicy() : "ADMIN_ONLY")
                .isDefault(request.getIsDefault() != null ? request.getIsDefault() : false)
                .build();

        CorkboardEntity saved = corkboardRepository.save(entity);
        log.info("コルクボード作成: scopeType={}, scopeId={}, boardId={}", scopeType, scopeId, saved.getId());
        return corkboardMapper.toBoardResponse(saved);
    }

    /**
     * 個人ボード詳細を取得する（カード・セクション含む）。
     */
    public CorkboardDetailResponse getPersonalBoard(Long userId, Long boardId) {
        CorkboardEntity board = corkboardRepository.findByIdAndOwnerId(boardId, userId)
                .orElseThrow(() -> new BusinessException(CorkboardErrorCode.BOARD_NOT_FOUND));
        return buildDetailResponse(board);
    }

    /**
     * スコープ別ボード詳細を取得する。
     */
    public CorkboardDetailResponse getScopedBoard(String scopeType, Long scopeId, Long boardId) {
        CorkboardEntity board = corkboardRepository.findByIdAndScopeTypeAndScopeId(boardId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(CorkboardErrorCode.BOARD_NOT_FOUND));
        return buildDetailResponse(board);
    }

    /**
     * 個人ボードを更新する。
     */
    @Transactional
    public CorkboardResponse updatePersonalBoard(Long userId, Long boardId, UpdateCorkboardRequest request) {
        CorkboardEntity board = corkboardRepository.findByIdAndOwnerId(boardId, userId)
                .orElseThrow(() -> new BusinessException(CorkboardErrorCode.BOARD_NOT_FOUND));

        board.update(
                request.getName(),
                request.getBackgroundStyle() != null ? request.getBackgroundStyle() : board.getBackgroundStyle(),
                board.getEditPolicy(),
                request.getIsDefault() != null ? request.getIsDefault() : board.getIsDefault()
        );

        CorkboardEntity saved = corkboardRepository.save(board);
        log.info("個人コルクボード更新: boardId={}", boardId);
        return corkboardMapper.toBoardResponse(saved);
    }

    /**
     * スコープ別ボードを更新する。
     */
    @Transactional
    public CorkboardResponse updateScopedBoard(String scopeType, Long scopeId, Long boardId,
                                                UpdateCorkboardRequest request) {
        CorkboardEntity board = corkboardRepository.findByIdAndScopeTypeAndScopeId(boardId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(CorkboardErrorCode.BOARD_NOT_FOUND));

        board.update(
                request.getName(),
                request.getBackgroundStyle() != null ? request.getBackgroundStyle() : board.getBackgroundStyle(),
                request.getEditPolicy() != null ? request.getEditPolicy() : board.getEditPolicy(),
                request.getIsDefault() != null ? request.getIsDefault() : board.getIsDefault()
        );

        CorkboardEntity saved = corkboardRepository.save(board);
        log.info("コルクボード更新: boardId={}", boardId);
        return corkboardMapper.toBoardResponse(saved);
    }

    /**
     * 個人ボードを削除する（論理削除）。
     */
    @Transactional
    public void deletePersonalBoard(Long userId, Long boardId) {
        CorkboardEntity board = corkboardRepository.findByIdAndOwnerId(boardId, userId)
                .orElseThrow(() -> new BusinessException(CorkboardErrorCode.BOARD_NOT_FOUND));
        board.softDelete();
        corkboardRepository.save(board);
        log.info("個人コルクボード削除: boardId={}", boardId);
    }

    /**
     * スコープ別ボードを削除する（論理削除）。
     * 共有ボード（TEAM/ORGANIZATION）の場合、{@link CorkboardEvent.Type#BOARD_DELETED} を発行し、
     * 購読中のクライアントへ削除を通知する。
     */
    @Transactional
    public void deleteScopedBoard(String scopeType, Long scopeId, Long boardId) {
        CorkboardEntity board = corkboardRepository.findByIdAndScopeTypeAndScopeId(boardId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(CorkboardErrorCode.BOARD_NOT_FOUND));
        board.softDelete();
        corkboardRepository.save(board);
        log.info("コルクボード削除: boardId={}", boardId);
        eventPublisher.publishEvent(CorkboardEvent.boardDeleted(boardId));
    }

    /**
     * ボードIDでボードを検索する（カード・セクション操作用の共有メソッド）。
     */
    public CorkboardEntity findBoardOrThrow(Long boardId) {
        return corkboardRepository.findById(boardId)
                .orElseThrow(() -> new BusinessException(CorkboardErrorCode.BOARD_NOT_FOUND));
    }

    private CorkboardDetailResponse buildDetailResponse(CorkboardEntity board) {
        List<CorkboardCardEntity> cards = cardRepository
                .findByCorkboardIdAndIsArchivedFalseOrderByZIndexDesc(board.getId());
        List<CorkboardGroupEntity> groups = groupRepository
                .findByCorkboardIdOrderByDisplayOrderAsc(board.getId());
        return corkboardMapper.toDetailResponse(board, cards, groups);
    }
}
