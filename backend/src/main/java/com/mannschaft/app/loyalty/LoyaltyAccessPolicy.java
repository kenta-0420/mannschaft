package com.mannschaft.app.loyalty;

import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.team.service.TeamService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * チームポイントの公開Serviceによる現在資格確認の入口。
 * <p>Facadeが各確認の順序を決定する。Policy自身はTXや業務処理を持たず、
 * 全体ロールによるTEAM資格の自動昇格を行わない。</p>
 */
@Component
@RequiredArgsConstructor
public class LoyaltyAccessPolicy {
    private final UserService userService;
    private final TeamService teamService;
    private final AccessControlService accessControlService;
    private final LoyaltyEligibilityPolicy eligibilityPolicy;

    /** 認証主体が現在も有効な個人であることを確認する。 */
    public void requireActiveActor(Long actor) {
        if (actor == null || actor <= 0) {
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }
        UserService.ActiveDisplayState state = userService.getActiveDisplayStates(List.of(actor)).get(actor);
        if (state == null || !state.active()) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /** 未承諾・終了・アーカイブ・削除済みTEAMを受付対象から除外する。 */
    public void requireActiveTeam(Long team) {
        if (team == null || team <= 0) {
            throw new BusinessException(LoyaltyErrorCode.LOYALTY_001);
        }
        TeamService.ActiveState state = teamService.getActiveStates(List.of(team)).get(team);
        if (state == null || !state.active()) {
            throw new BusinessException(LoyaltyErrorCode.LOYALTY_001);
        }
    }

    /** 当該TEAMの管理者、または明示委任された副管理者だけを許可する。 */
    public void requireOperator(Long actor, Long team) {
        if (actor == null || actor <= 0 || team == null || team <= 0
                || !accessControlService.hasAdminOrPermissionInScope(actor, team, "TEAM", "TEAM_LOYALTY_OPERATE")) {
            throw new BusinessException(LoyaltyErrorCode.LOYALTY_002);
        }
    }

    /** 当該TEAMへ明示的に割り当てられた管理者だけを許可する。 */
    public void requireSettingsAdmin(Long actor, Long team) {
        if (actor == null || actor <= 0 || team == null || team <= 0
                || !accessControlService.isAdmin(actor, team, "TEAM")) {
            throw new BusinessException(LoyaltyErrorCode.LOYALTY_002);
        }
    }

    /** 本人とTEAM在籍の現在状態を公開Serviceから取得して対象条件を判定する。 */
    public void requireEligible(Long user, Long team, Audience audience) {
        if (user == null || user <= 0 || audience == null) {
            throw new BusinessException(LoyaltyErrorCode.LOYALTY_003);
        }
        UserService.ActiveDisplayState userState = userService.getActiveDisplayStates(List.of(user)).get(user);
        boolean activeUser = userState != null && userState.active();
        AccessControlService.TeamMembershipState membership = null;
        if (activeUser && team != null && team > 0 && audience != Audience.ALL_AUTHENTICATED) {
            AccessControlService.TeamUserKey key = new AccessControlService.TeamUserKey(team, user);
            membership = accessControlService.getTeamMembershipStates(List.of(key)).get(key);
        }
        if (!eligibilityPolicy.isEligible(audience, activeUser,
                membership != null && membership.active(),
                membership != null && membership.regularMemberOrAbove())) {
            throw new BusinessException(LoyaltyErrorCode.LOYALTY_003);
        }
    }
}
