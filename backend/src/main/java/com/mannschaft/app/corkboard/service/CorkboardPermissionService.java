package com.mannschaft.app.corkboard.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.corkboard.CorkboardErrorCode;
import com.mannschaft.app.corkboard.entity.CorkboardEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * F09.8 Phase A-2: 共有ボード（TEAM/ORGANIZATION）の {@code edit_policy} を検証する。
 *
 * <p>判定ルール（設計書 §2 共有ボードの編集権限モデル）:</p>
 * <ul>
 *   <li>{@code PERSONAL} ボード &rarr; ボード所有者のみ編集可（{@link CorkboardService} 側で
 *       {@code findByIdAndOwnerId} により担保済み。本サービスではスキップ）</li>
 *   <li>{@code TEAM/ORGANIZATION} かつ {@code edit_policy = ADMIN_ONLY} &rarr;
 *       当該スコープの ADMIN/DEPUTY_ADMIN のみ</li>
 *   <li>{@code TEAM/ORGANIZATION} かつ {@code edit_policy = ALL_MEMBERS} &rarr;
 *       当該スコープのメンバー全員（SUPPORTER/GUEST 等の非メンバーは拒否）</li>
 * </ul>
 *
 * <p>違反時は {@link CorkboardErrorCode#INSUFFICIENT_PERMISSION}（CORKBOARD_009 / 403）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CorkboardPermissionService {

    private static final String SCOPE_PERSONAL = "PERSONAL";
    private static final String SCOPE_TEAM = "TEAM";
    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";

    private static final String POLICY_ADMIN_ONLY = "ADMIN_ONLY";
    private static final String POLICY_ALL_MEMBERS = "ALL_MEMBERS";

    private final AccessControlService accessControlService;

    /**
     * 共有ボードのカード/セクション CRUD・位置更新の編集権限をチェックする。
     * 違反時は {@link BusinessException}（CORKBOARD_009 / 403）。
     *
     * @param board  対象ボードエンティティ
     * @param userId 操作ユーザーID
     */
    public void checkEditPermission(CorkboardEntity board, Long userId) {
        if (board == null || userId == null) {
            throw new BusinessException(CorkboardErrorCode.INSUFFICIENT_PERMISSION);
        }
        String scopeType = board.getScopeType();

        // PERSONAL は呼び出し側（CorkboardService）で所有者チェック済み
        if (SCOPE_PERSONAL.equals(scopeType)) {
            if (board.getOwnerId() != null && !board.getOwnerId().equals(userId)) {
                throw new BusinessException(CorkboardErrorCode.INSUFFICIENT_PERMISSION);
            }
            return;
        }

        if (!SCOPE_TEAM.equals(scopeType) && !SCOPE_ORGANIZATION.equals(scopeType)) {
            // 想定外スコープ → 拒否
            throw new BusinessException(CorkboardErrorCode.INSUFFICIENT_PERMISSION);
        }

        Long scopeId = board.getScopeId();
        String policy = board.getEditPolicy() != null ? board.getEditPolicy() : POLICY_ADMIN_ONLY;

        boolean allowed = switch (policy) {
            case POLICY_ADMIN_ONLY -> accessControlService.isAdminOrAbove(userId, scopeId, scopeType);
            case POLICY_ALL_MEMBERS -> accessControlService.isMember(userId, scopeId, scopeType);
            default -> false;
        };

        if (!allowed) {
            log.warn("コルクボード編集権限なし: boardId={}, userId={}, scope={}, policy={}",
                    board.getId(), userId, scopeType, policy);
            throw new BusinessException(CorkboardErrorCode.INSUFFICIENT_PERMISSION);
        }
    }
}
