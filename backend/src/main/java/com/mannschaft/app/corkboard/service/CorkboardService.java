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
    private final CorkboardAccessGuard corkboardAccessGuard;

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
     *
     * <p>認可根治 Wave3-B8: 是正前は scopeType/scopeId を知っていれば非所属ユーザーでも
     * ボード名・背景設定等を列挙できる BOLA だった。当該スコープのメンバーのみ許可する。</p>
     *
     * @param scopeType スコープ種別 ({@code TEAM} / {@code ORGANIZATION})
     * @param scopeId   スコープID
     * @param userId    操作ユーザーID
     */
    public List<CorkboardResponse> listScopedBoards(String scopeType, Long scopeId, Long userId) {
        if (!accessControlService.isMember(userId, scopeId, scopeType)) {
            log.warn("コルクボード一覧閲覧権限なし: userId={}, scope={}, scopeId={}", userId, scopeType, scopeId);
            throw new BusinessException(CorkboardErrorCode.INSUFFICIENT_PERMISSION);
        }
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
     *
     * <p>認可根治 Wave3-B8: ボード作成は当該スコープの ADMIN/DEPUTY_ADMIN のみ許可する。</p>
     *
     * @param scopeType スコープ種別 ({@code TEAM} / {@code ORGANIZATION})
     * @param scopeId   スコープID
     * @param userId    操作ユーザーID
     * @param request   作成リクエスト
     */
    @Transactional
    public CorkboardResponse createScopedBoard(String scopeType, Long scopeId, Long userId,
                                                CreateCorkboardRequest request) {
        if (!accessControlService.isAdminOrAbove(userId, scopeId, scopeType)) {
            log.warn("コルクボード作成権限なし: userId={}, scope={}, scopeId={}", userId, scopeType, scopeId);
            throw new BusinessException(CorkboardErrorCode.INSUFFICIENT_PERMISSION);
        }
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
     *
     * <p>認可は {@link CorkboardAccessGuard#requireOwnedBoard}（所有者本人のみ・
     * 他者所有／不存在は 404 秘匿）。</p>
     */
    public CorkboardDetailResponse getPersonalBoard(Long userId, Long boardId) {
        CorkboardEntity board = corkboardAccessGuard.requireOwnedBoard(userId, boardId);
        return buildDetailResponse(board, userId);
    }

    /**
     * スコープ別ボード詳細を取得する。
     *
     * <p>F09.8 件A: viewerCanEdit 算出のため userId を受け取る。</p>
     *
     * <p>認可根治 Wave3-B8 (BOLA 根治): 是正前は {@code findByIdAndScopeTypeAndScopeId} が
     * board と path scope の整合性のみ担保し、呼出者が当該スコープのメンバーかは未検証だった
     * （scopeId/boardId さえ知っていればカード・セクション全内容を非所属者が閲覧できた）。
     * board 取得後、<b>entity 由来の scope</b>（{@code board.getScopeType()}/{@code getScopeId()}）で
     * {@link com.mannschaft.app.common.AccessControlService#isMember} を検証する（path 鵜呑み禁止）。</p>
     *
     * @param scopeType スコープ種別 ({@code TEAM} / {@code ORGANIZATION})
     * @param scopeId   スコープID
     * @param boardId   ボードID
     * @param userId    操作ユーザーID（認可判定 兼 viewerCanEdit 判定用）
     */
    public CorkboardDetailResponse getScopedBoard(String scopeType, Long scopeId, Long boardId, Long userId) {
        CorkboardEntity board = corkboardRepository.findByIdAndScopeTypeAndScopeId(boardId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(CorkboardErrorCode.BOARD_NOT_FOUND));
        if (!accessControlService.isMember(userId, board.getScopeId(), board.getScopeType())) {
            log.warn("コルクボード詳細閲覧権限なし: boardId={}, userId={}, scope={}, scopeId={}",
                    boardId, userId, board.getScopeType(), board.getScopeId());
            throw new BusinessException(CorkboardErrorCode.INSUFFICIENT_PERMISSION);
        }
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
     *
     * <p>認可は {@link CorkboardAccessGuard#requireOwnedBoard}（所有者本人のみ・
     * 他者所有／不存在は 404 秘匿）。{@code editPolicy} は個人ボードでは変更させない
     * （現値を据え置く）。</p>
     */
    @Transactional
    public CorkboardResponse updatePersonalBoard(Long userId, Long boardId, UpdateCorkboardRequest request) {
        CorkboardEntity board = corkboardAccessGuard.requireOwnedBoard(userId, boardId);

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
     *
     * <p>認可根治 Wave3-B8 (BOLA 根治): board 取得後、<b>entity 由来の scope</b>で
     * ADMIN/DEPUTY_ADMIN を要求する（path 鵜呑み禁止）。update は {@code editPolicy} 改変を伴い
     * 権限昇格し得るため、{@link CorkboardPermissionService#checkEditPermission} の
     * {@code ALL_MEMBERS} 水準では不足であり、必ず ADMIN 水準で判定する。</p>
     *
     * @param scopeType スコープ種別 ({@code TEAM} / {@code ORGANIZATION})
     * @param scopeId   スコープID
     * @param boardId   ボードID
     * @param userId    操作ユーザーID
     * @param request   更新リクエスト
     */
    @Transactional
    public CorkboardResponse updateScopedBoard(String scopeType, Long scopeId, Long boardId, Long userId,
                                                UpdateCorkboardRequest request) {
        CorkboardEntity board = corkboardRepository.findByIdAndScopeTypeAndScopeId(boardId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(CorkboardErrorCode.BOARD_NOT_FOUND));
        if (!accessControlService.isAdminOrAbove(userId, board.getScopeId(), board.getScopeType())) {
            log.warn("コルクボード更新権限なし: boardId={}, userId={}, scope={}, scopeId={}",
                    boardId, userId, board.getScopeType(), board.getScopeId());
            throw new BusinessException(CorkboardErrorCode.INSUFFICIENT_PERMISSION);
        }

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
     *
     * <p>認可は {@link CorkboardAccessGuard#requireOwnedBoard}（所有者本人のみ・
     * 他者所有／不存在は 404 秘匿）。</p>
     */
    @Transactional
    public void deletePersonalBoard(Long userId, Long boardId) {
        CorkboardEntity board = corkboardAccessGuard.requireOwnedBoard(userId, boardId);
        board.softDelete();
        corkboardRepository.save(board);
        log.info("個人コルクボード削除: boardId={}", boardId);
    }

    /**
     * スコープ別ボードを削除する（論理削除）。
     * 共有ボード（TEAM/ORGANIZATION）の場合、{@link CorkboardEvent.Type#BOARD_DELETED} を発行し、
     * 購読中のクライアントへ削除を通知する。
     *
     * <p>認可根治 Wave3-B8 (BOLA 根治): board 取得後、<b>entity 由来の scope</b>で
     * ADMIN/DEPUTY_ADMIN を要求する（path 鵜呑み禁止）。</p>
     *
     * @param scopeType スコープ種別 ({@code TEAM} / {@code ORGANIZATION})
     * @param scopeId   スコープID
     * @param boardId   ボードID
     * @param userId    操作ユーザーID
     */
    @Transactional
    public void deleteScopedBoard(String scopeType, Long scopeId, Long boardId, Long userId) {
        CorkboardEntity board = corkboardRepository.findByIdAndScopeTypeAndScopeId(boardId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(CorkboardErrorCode.BOARD_NOT_FOUND));
        if (!accessControlService.isAdminOrAbove(userId, board.getScopeId(), board.getScopeType())) {
            log.warn("コルクボード削除権限なし: boardId={}, userId={}, scope={}, scopeId={}",
                    boardId, userId, board.getScopeType(), board.getScopeId());
            throw new BusinessException(CorkboardErrorCode.INSUFFICIENT_PERMISSION);
        }
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
