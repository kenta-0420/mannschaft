package com.mannschaft.app.corkboard.service;

import com.mannschaft.app.common.AccessControlService;
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
    private final AccessControlService accessControlService;
    private final CorkboardPermissionService corkboardPermissionService;

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
        return buildDetailResponse(board, userId);
    }

    /**
     * スコープ別ボード詳細を取得する。
     *
     * <p>F09.8 件A: viewerCanEdit 算出のため userId を受け取る。
     * 既存の閲覧権限チェック（{@code findByIdAndScopeTypeAndScopeId}）はスコープ整合性のみ
     * 担保しており、メンバーシップチェックは行わない（既存挙動を維持）。</p>
     *
     * @param scopeType スコープ種別 ({@code TEAM} / {@code ORGANIZATION})
     * @param scopeId   スコープID
     * @param boardId   ボードID
     * @param userId    操作ユーザーID（viewerCanEdit 判定用）
     */
    public CorkboardDetailResponse getScopedBoard(String scopeType, Long scopeId, Long boardId, Long userId) {
        CorkboardEntity board = corkboardRepository.findByIdAndScopeTypeAndScopeId(boardId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(CorkboardErrorCode.BOARD_NOT_FOUND));
        return buildDetailResponse(board, userId);
    }

    /**
     * 組織ボード詳細を取得する。所属チェックを実施する。
     *
     * @param orgId   組織ID
     * @param boardId ボードID
     * @param userId  操作ユーザーID
     * @return ボード詳細レスポンス
     */
    public CorkboardDetailResponse getOrganizationBoardDetail(Long orgId, Long boardId, Long userId) {
        CorkboardEntity board = corkboardRepository
                .findByIdAndScopeTypeAndScopeId(boardId, "ORGANIZATION", orgId)
                .orElseThrow(() -> new BusinessException(CorkboardErrorCode.BOARD_NOT_FOUND));
        if (!accessControlService.isMember(userId, orgId, "ORGANIZATION")) {
            log.warn("組織コルクボード閲覧権限なし: boardId={}, userId={}, orgId={}", boardId, userId, orgId);
            throw new BusinessException(CorkboardErrorCode.INSUFFICIENT_PERMISSION);
        }
        return buildDetailResponse(board, userId);
    }

    /**
     * boardId 単独でボード詳細を取得する（scope-agnostic）。
     *
     * <p>boardId からボードを引き当て、{@code scope_type} に応じて適切な閲覧権限チェックを行う。</p>
     * <ul>
     *   <li>{@code PERSONAL} &rarr; 所有者のみ</li>
     *   <li>{@code TEAM} &rarr; チームメンバーのみ</li>
     *   <li>{@code ORGANIZATION} &rarr; 組織メンバーのみ</li>
     * </ul>
     *
     * @param boardId ボードID
     * @param userId  操作ユーザーID
     * @return ボード詳細レスポンス
     * @throws BusinessException ボード未存在 ({@code CORKBOARD_001} / 404) または権限不足 ({@code CORKBOARD_009} / 403)
     */
    public CorkboardDetailResponse getBoardDetailByIdOnly(Long boardId, Long userId) {
        CorkboardEntity board = corkboardRepository.findById(boardId)
                .orElseThrow(() -> new BusinessException(CorkboardErrorCode.BOARD_NOT_FOUND));

        String scopeType = board.getScopeType();
        switch (scopeType) {
            case "PERSONAL" -> {
                if (board.getOwnerId() == null || !board.getOwnerId().equals(userId)) {
                    log.warn("個人コルクボード閲覧権限なし: boardId={}, userId={}, ownerId={}",
                            boardId, userId, board.getOwnerId());
                    throw new BusinessException(CorkboardErrorCode.INSUFFICIENT_PERMISSION);
                }
            }
            case "TEAM", "ORGANIZATION" -> {
                Long scopeId = board.getScopeId();
                if (scopeId == null || !accessControlService.isMember(userId, scopeId, scopeType)) {
                    log.warn("共有コルクボード閲覧権限なし: boardId={}, userId={}, scope={}, scopeId={}",
                            boardId, userId, scopeType, scopeId);
                    throw new BusinessException(CorkboardErrorCode.INSUFFICIENT_PERMISSION);
                }
            }
            default -> {
                log.warn("未知のスコープタイプ: boardId={}, scopeType={}", boardId, scopeType);
                throw new BusinessException(CorkboardErrorCode.INSUFFICIENT_PERMISSION);
            }
        }
        return buildDetailResponse(board, userId);
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

    /**
     * ボード詳細レスポンスを組み立てる。
     *
     * <p>F09.8 件A: 閲覧ユーザーの編集権限を {@link CorkboardPermissionService#canEdit} で判定し、
     * {@code viewerCanEdit} としてレスポンスに含める。フロントの編集ボタン disabled 制御に使う。</p>
     */
    private CorkboardDetailResponse buildDetailResponse(CorkboardEntity board, Long userId) {
        List<CorkboardCardEntity> cards = cardRepository
                .findByCorkboardIdAndIsArchivedFalseOrderByZIndexDesc(board.getId());
        List<CorkboardGroupEntity> groups = groupRepository
                .findByCorkboardIdOrderByDisplayOrderAsc(board.getId());
        boolean viewerCanEdit = corkboardPermissionService.canEdit(board, userId);
        return corkboardMapper.toDetailResponse(board, cards, groups, viewerCanEdit);
    }
}
