package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.membership.ScopeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 確認通知（F04.9）の ID 付き EP の認可と、存在オラクルの封鎖（CMP-260923-0954 W3b）。
 *
 * <p>トランザクションを持たない。Team / Org の Controller と
 * {@link ConfirmableNotificationRecipientPageFacade} が、tx 本体の Service を呼ぶ<b>前</b>に使う。</p>
 *
 * <h2>拒否の作り分け</h2>
 * <ul>
 *   <li><b>越境</b>（当該スコープに何の関わりも無い）: 呼び出し側が渡す {@code notFoundCode} を投げる。
 *       不在 ID のときに投げるコードそのものであること（専用コードを作ると、それ自体が「実在する」という答えになる）。</li>
 *   <li><b>当該スコープの関係者の権限不足</b>: 403（{@link CommonErrorCode#COMMON_002}）。
 *       関係者とは、在籍者（memberships）・user_roles に ADMIN/DEPUTY_ADMIN を持つ者・SYSTEM_ADMIN。
 *       SYSTEM_ADMIN は是正前の応答（403）を保つ（新規許可も 404 化もしない。裁可 2026-09-30）。</li>
 * </ul>
 *
 * <p>許可経路の認可クエリは是正前の判定関数 1 回ぶんのまま増やさない（拒否経路でだけ追加で引く）。
 * Gate（{@code ScopeConcealingAccessGate}）は SYSTEM_ADMIN を通してしまうため使わない。</p>
 */
@Component
@RequiredArgsConstructor
public class ConfirmableScopeAuthorizer {

    /** F04.9 §2 が定める確認通知の送信権限（CMP-260909-1141）。 */
    public static final String SEND_NOTIFICATION = "SEND_NOTIFICATION";

    private final AccessControlService accessControlService;

    /**
     * 閲覧系の認可（スコープに在籍していること）。許可経路の判定は {@code checkMembership} と同じ 1 回。
     *
     * @throws BusinessException 関係者の権限不足は COMMON_002、越境は {@code notFoundCode}
     */
    public void requireMember(Long userId, ScopeType scopeType, Long scopeId, ErrorCode notFoundCode) {
        if (accessControlService.isMember(userId, scopeId, scopeType.name())) {
            return;
        }
        throw denial(userId, scopeType, scopeId, notFoundCode);
    }

    /**
     * 管理操作の認可（ADMIN、または SEND_NOTIFICATION を持つ DEPUTY_ADMIN）。
     * 許可経路の判定は {@code checkAdminOrHasPermissionInScope} と同じ 1 回。
     *
     * @throws BusinessException 関係者の権限不足は COMMON_002、越境は {@code notFoundCode}
     */
    public void requireSendPermission(Long userId, ScopeType scopeType, Long scopeId, ErrorCode notFoundCode) {
        if (accessControlService.hasAdminOrPermissionInScope(userId, scopeId, scopeType.name(), SEND_NOTIFICATION)) {
            return;
        }
        throw denial(userId, scopeType, scopeId, notFoundCode);
    }

    /**
     * 許可されなかった呼び出しの拒否を作る（拒否経路でだけ呼ぶ）。
     *
     * @return 関係者なら COMMON_002（403）、無関係なら {@code notFoundCode}
     */
    public BusinessException denial(Long userId, ScopeType scopeType, Long scopeId, ErrorCode notFoundCode) {
        String scope = scopeType.name();
        if (accessControlService.isMember(userId, scopeId, scope)
                || accessControlService.isSystemAdmin(userId)
                // 在籍行を持たず user_roles だけに ADMIN/DEPUTY_ADMIN を持つ者は関係者として 403（404 に化けさせない）。
                || accessControlService.isAdminOrAbove(userId, scopeId, scope)) {
            return new BusinessException(CommonErrorCode.COMMON_002);
        }
        return new BusinessException(notFoundCode);
    }
}
