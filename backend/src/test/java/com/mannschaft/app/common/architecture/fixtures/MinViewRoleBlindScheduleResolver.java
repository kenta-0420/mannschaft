package com.mannschaft.app.common.architecture.fixtures;

/**
 * fixture: 射影に {@code minViewRole} があるにもかかわらず、閲覧判定で<b>一切読まない</b> Resolver。
 *
 * <p>CMP-017b が根治する事故そのものの形（列を足したが読まない）。番人はこれを
 * <b>違反として検出</b>しなければならない（偽陰性ゼロの担保）。</p>
 */
public class MinViewRoleBlindScheduleResolver {

    /** 射影の id だけを見て判定する（閾値を評価していない）。 */
    public boolean canView(MinViewRoleFixtureProjection row, String viewerRoleName) {
        return row != null && row.id() != null && viewerRoleName != null;
    }
}
