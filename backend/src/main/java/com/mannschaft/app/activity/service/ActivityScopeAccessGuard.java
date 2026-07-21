package com.mannschaft.app.activity.service;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 活動記録（F06.4）のスコープ認可ガード。
 *
 * <p>activity ドメインの各 Service / Controller が共通で使う「スコープ所属・スコープ管理者」判定を
 * 本クラスへ集約する。{@code bulletin} ドメインの {@code BulletinAccessGuard} を金型とする。</p>
 *
 * <h2>スコープ別の方針（網羅的ディスパッチ）</h2>
 * <p>{@link ActivityScopeType} の全値を明示的に扱い、<strong>未対応の値は既定で拒否する
 * （fail-closed）</strong>。分岐の追加漏れが「素通し」ではなく「拒否」に倒れるようにするための規律であり、
 * 将来 {@code ActivityScopeType} に値が追加された場合も既定で拒否される。</p>
 * <ul>
 *   <li>{@code TEAM} / {@code ORGANIZATION}: {@link AccessControlService} 経由で所属・ロールを実効認可する。</li>
 *   <li>{@code COMMITTEE}: メンバーシップ／ロール基盤（{@link AccessControlService}）の解決対象外のため、
 *       本ガードでは認可を与えず拒否する。これは同ドメインの
 *       {@code ActivityResultVisibilityProjection}（F00 共通可視性基盤）が COMMITTEE を
 *       fail-closed として扱う方針と一致させたものである。
 *       委員会スコープを機能として開くときは、委員会メンバーシップ
 *       （{@code committee} ドメイン）を解決する分岐をここに実装すること。</li>
 * </ul>
 *
 * <p>認可違反は共通 403（{@link CommonErrorCode#COMMON_002}）を用いる（activity 専用の認可エラーは作らない）。</p>
 */
@Component
@RequiredArgsConstructor
public class ActivityScopeAccessGuard {

    private final AccessControlService accessControlService;

    /**
     * 当該スコープが {@link AccessControlService} のロール／メンバーシップ基盤で
     * 認可判定可能か（= TEAM / ORGANIZATION か）を返す。
     *
     * <p>網羅的ディスパッチ。{@code null} および解決対象外のスコープでは {@code false} を返し、
     * 呼び出し側が拒否へ倒す。</p>
     */
    private boolean isRoleManagedScope(ActivityScopeType scopeType) {
        if (scopeType == null) {
            return false;
        }
        return switch (scopeType) {
            case TEAM, ORGANIZATION -> true;
            case COMMITTEE -> false;
        };
    }

    /**
     * ロール基盤で解決できないスコープを拒否する。
     *
     * @throws BusinessException 解決対象外スコープ（COMMON_002）
     */
    private void requireRoleManagedScope(ActivityScopeType scopeType) {
        if (!isRoleManagedScope(scopeType)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * スコープ所属メンバーであることを検証する（参照系・作成系）。
     *
     * @throws BusinessException 非メンバー、または解決対象外スコープ（COMMON_002）
     */
    public void checkMembership(Long userId, ActivityScopeType scopeType, Long scopeId) {
        requireRoleManagedScope(scopeType);
        accessControlService.checkMembership(userId, scopeId, scopeType.name());
    }

    /**
     * スコープ管理者（ADMIN / DEPUTY_ADMIN）であることを検証する（更新・削除系）。
     *
     * @throws BusinessException 非管理者、または解決対象外スコープ（COMMON_002）
     */
    public void checkAdminOrAbove(Long userId, ActivityScopeType scopeType, Long scopeId) {
        requireRoleManagedScope(scopeType);
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType.name());
    }

    /**
     * 作成者本人、またはスコープ管理者であることを検証する（活動記録の公開・更新・削除）。
     *
     * <p>解決対象外スコープでは作成者本人であっても拒否する（fail-closed）。</p>
     *
     * @throws BusinessException 本人でも管理者でもない、または解決対象外スコープ（COMMON_002）
     */
    public void checkAuthorOrAdmin(Long userId, Long authorUserId,
                                   ActivityScopeType scopeType, Long scopeId) {
        requireRoleManagedScope(scopeType);
        if (userId != null && userId.equals(authorUserId)) {
            return;
        }
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType.name());
    }

    /**
     * リソース所有者本人、またはスコープ管理者であることを検証する（コメント削除）。
     *
     * @throws BusinessException 本人でも管理者でもない、または解決対象外スコープ（COMMON_002）
     */
    public void checkOwnerOrAdmin(Long currentUserId, Long resourceOwnerId,
                                  ActivityScopeType scopeType, Long scopeId) {
        requireRoleManagedScope(scopeType);
        accessControlService.checkOwnerOrAdmin(
                currentUserId, resourceOwnerId, scopeId, scopeType.name());
    }

    /**
     * スコープ管理者（ADMIN / DEPUTY_ADMIN）かどうかを返す。
     *
     * <p>権限の有無を問う述語であるため、解決対象外スコープでは
     * <strong>権限なし（{@code false}）</strong>を返す（fail-closed）。</p>
     */
    public boolean isAdminOrAbove(Long userId, ActivityScopeType scopeType, Long scopeId) {
        return isRoleManagedScope(scopeType)
                && accessControlService.isAdminOrAbove(userId, scopeId, scopeType.name());
    }
}
