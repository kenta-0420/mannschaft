package com.mannschaft.app.common.architecture.fixtures;

import com.mannschaft.app.schedule.MinViewRole;

/**
 * fixture: 射影の {@code minViewRole} を閲覧判定で<b>実際に読む</b> Resolver。
 *
 * <p>番人はこれを<b>合格</b>と判定しなければならない（偽陽性ゼロの担保）。</p>
 */
public class MinViewRoleAwareScheduleResolver {

    /** 閾値を実際に評価して判定する。 */
    public boolean canView(MinViewRoleFixtureProjection row, String viewerRoleName) {
        if (row == null || viewerRoleName == null) {
            return false;
        }
        MinViewRole threshold = row.minViewRole();
        if (threshold == null || threshold == MinViewRole.ANYONE) {
            return true;
        }
        return switch (threshold) {
            case ANYONE -> true;
            case SUPPORTER_PLUS -> !"GUEST".equals(viewerRoleName);
            case MEMBER_PLUS -> !"GUEST".equals(viewerRoleName) && !"SUPPORTER".equals(viewerRoleName);
            case ADMIN_ONLY -> "ADMIN".equals(viewerRoleName) || "DEPUTY_ADMIN".equals(viewerRoleName);
        };
    }
}
