package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.common.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * F06.4.1 チーム側の認可（親設計 §5「Policy→AccessControlService で所属・既存テンプレート管理権限を確認し、
 * 他ドメイン Repository を呼ばない」）。
 *
 * <p>判定は team_id だけで行い、親組織の有無・一致は使わない（2026-11-18 マスター裁定）。
 * SYSTEM_ADMIN もチームの所属・管理権限をバイパスしない（{@link AccessControlService} の該当判定は昇格しない）。
 * 拒否はいずれも {@code COMMON_002}（403）。</p>
 */
@Component
@RequiredArgsConstructor
public class EffortTeamAccessGuard {

    private static final String TEAM = "TEAM";

    private final AccessControlService accessControl;

    /** チームに現在所属していることを要求する（脱退済み・非所属は 403）。 */
    public void requireMember(Long userId, Long teamId) {
        accessControl.checkMembership(userId, teamId, TEAM);
    }

    /** 既存の TEAM テンプレート管理権限（ADMIN・DEPUTY_ADMIN）を要求する（MEMBER・非所属は 403）。 */
    public void requireTemplateManager(Long userId, Long teamId) {
        accessControl.checkAdminOrAbove(userId, teamId, TEAM);
    }
}
