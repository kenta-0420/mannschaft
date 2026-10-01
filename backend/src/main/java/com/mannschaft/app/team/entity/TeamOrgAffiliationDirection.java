package com.mannschaft.app.team.entity;

/**
 * チーム加盟の起点（F01.2.1 §5.3・§5.4）。
 *
 * <p>ORG_INVITE=組織からの招待 / TEAM_APPLY=チームからの申請。</p>
 */
public enum TeamOrgAffiliationDirection {
    ORG_INVITE,
    TEAM_APPLY
}
