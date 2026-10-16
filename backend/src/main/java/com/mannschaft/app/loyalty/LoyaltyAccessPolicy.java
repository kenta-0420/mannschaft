package com.mannschaft.app.loyalty;

import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * チームポイントの公開Serviceによる現在資格確認の入口。
 * <p>実Bean・実DBの先行試練に接続するためのno-op骨格であり、認可の実装完了ではない。
 * Facadeが各確認の順序を決定する。Policy自身はTXや業務処理を持たない。</p>
 */
@Component
@RequiredArgsConstructor
public class LoyaltyAccessPolicy {
    private final UserService userService;
    private final TeamService teamService;
    private final AccessControlService accessControlService;
    private final LoyaltyEligibilityPolicy eligibilityPolicy;

    public void requireActiveActor(Long actor) {
    }

    public void requireActiveTeam(Long team) {
    }

    public void requireOperator(Long actor, Long team) {
    }

    public void requireSettingsAdmin(Long actor, Long team) {
    }

    public void requireEligible(Long user, Long team, Audience audience) {
    }
}
