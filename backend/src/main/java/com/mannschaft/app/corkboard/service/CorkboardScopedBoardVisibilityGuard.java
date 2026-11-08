package com.mannschaft.app.corkboard.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.corkboard.CorkboardErrorCode;
import com.mannschaft.app.corkboard.entity.CorkboardEntity;
import com.mannschaft.app.corkboard.repository.CorkboardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 共有ボード（TEAM / ORGANIZATION）の書込前に通す「閲覧可否の門」。CMP-260820-1422 存在オラクル一掃。
 *
 * <h2>保証する内容</h2>
 * <p>当該ボードを閲覧できない者（スコープの非メンバーかつ ADMIN 水準でもない者）には、
 * 不在と同一の {@link CorkboardErrorCode#BOARD_NOT_FOUND}（404）を返して boardId の実在を秘匿する。
 * 閲覧できる者はそのまま通し、ADMIN 水準の判定と権限不足の
 * {@link CorkboardErrorCode#INSUFFICIENT_PERMISSION}（403）は {@link CorkboardService} 側に委ねる
 * （メンバーはボードを一覧・詳細で既に閲覧できるため、403 を返しても存在情報は新たに漏れない）。</p>
 *
 * <h2>なぜ {@code @Transactional} を付けず、Controller から先に呼ぶのか</h2>
 * <p>{@link CorkboardService} はクラス単位で {@code @Transactional} のため、そこから
 * {@link AccessControlService#isMember} を呼ぶと membership ドメインの Repository へ推移的に到達し、
 * 取引が corkboard ドメインの外へ越境する（D-3T 番人、CLAUDE.md DB 設計の原則 #5）。
 * 本部品は取引を持たず、他ドメインの判定はそれぞれの取引で行う（{@code BlogScopeAccessGuard} と同じ型）。</p>
 */
@Component
@RequiredArgsConstructor
public class CorkboardScopedBoardVisibilityGuard {

    private final CorkboardRepository corkboardRepository;
    private final AccessControlService accessControlService;

    /**
     * 指定スコープ内のボードを、呼出者が閲覧できることを確かめる。
     *
     * <p>ボードが指定スコープに無い場合は何もしない（不在の 404 は {@link CorkboardService} が
     * 同一の {@link CorkboardErrorCode#BOARD_NOT_FOUND} で返す）。</p>
     *
     * @param scopeType スコープ種別 ({@code TEAM} / {@code ORGANIZATION})
     * @param scopeId   スコープID（パス由来）
     * @param boardId   ボードID
     * @param userId    操作ユーザーID
     * @throws BusinessException 閲覧できない者（{@link CorkboardErrorCode#BOARD_NOT_FOUND} / 404）
     */
    public void requireVisible(String scopeType, Long scopeId, Long boardId, Long userId) {
        CorkboardEntity board = corkboardRepository.findByIdAndScopeTypeAndScopeId(boardId, scopeType, scopeId)
                .orElse(null);
        if (board == null) {
            return;
        }
        // entity 由来の scope で判定する（path 鵜呑み禁止）
        Long boardScopeId = board.getScopeId();
        String boardScopeType = board.getScopeType();
        if (!accessControlService.isMember(userId, boardScopeId, boardScopeType)
                && !accessControlService.isAdminOrAbove(userId, boardScopeId, boardScopeType)) {
            throw new BusinessException(CorkboardErrorCode.BOARD_NOT_FOUND);
        }
    }
}
