package com.mannschaft.app.social.announcement.audience;

import com.mannschaft.app.dashboard.dto.OrgDashboardResponse;
import com.mannschaft.app.dashboard.dto.TeamDashboardResponse;
import com.mannschaft.app.dashboard.service.DashboardService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 6-C（告知の表示判定）の IT 共通フィクスチャ。
 *
 * <p>設計書 §16 の記号: 組織X に G1・G2・G3（この並び順）と T1∈G1・T2∈G2・T3∈G3・T4（未分類）。
 * U1〜U4 はそれぞれ T1〜T4 のメンバーで組織X のメンバー、XO は組織X の直属メンバー。
 * 告知は 6-A の送信 API で実際に作り、表示は DashboardService（実経路）で観測する。</p>
 */
abstract class AbstractAudienceDisplayIT extends AbstractBroadcastAudienceIT {

    protected static final Long U1 = 940603011L;
    protected static final Long U2 = 940603012L;
    protected static final Long U3 = 940603013L;
    protected static final Long U4 = 940603014L;

    @Autowired
    protected DashboardService dashboardService;

    protected OrganizationEntity orgX;
    protected OrgTeamGroupEntity g1;
    protected OrgTeamGroupEntity g2;
    protected OrgTeamGroupEntity g3;
    protected TeamEntity t1;
    protected TeamEntity t2;
    protected TeamEntity t3;
    protected TeamEntity t4;

    protected void setUpOrgX() {
        orgX = newOrg(true);
        g1 = newGroup(orgX.getId(), "G1", 0);
        g2 = newGroup(orgX.getId(), "G2", 1);
        g3 = newGroup(orgX.getId(), "G3", 2);
        t1 = activeTeam(orgX.getId(), g1.getId(), "T1");
        t2 = activeTeam(orgX.getId(), g2.getId(), "T2");
        t3 = activeTeam(orgX.getId(), g3.getId(), "T3");
        t4 = activeTeam(orgX.getId(), null, "T4");
        seedOrgPerson(XA, orgX.getId(), "ADMIN");
        seedOrgPerson(XO, orgX.getId(), "MEMBER");
        seedTeamViewer(U1, orgX.getId(), t1.getId());
        seedTeamViewer(U2, orgX.getId(), t2.getId());
        seedTeamViewer(U3, orgX.getId(), t3.getId());
        seedTeamViewer(U4, orgX.getId(), t4.getId());
        flushAndClear();
    }

    /** 組織のメンバーであり、かつチームのメンバーでもある閲覧者を作る。 */
    protected void seedTeamViewer(Long userId, Long orgId, Long teamId) {
        seedOrgPerson(userId, orgId, "MEMBER");
        MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
    }

    // ───────── 送信 ─────────

    protected long send(Map<String, Object> audience) throws Exception {
        return sendAs(XA, orgX.getId(), audience);
    }

    protected long sendAs(Long actor, Long orgId, Map<String, Object> audience) throws Exception {
        return feedIdOf(broadcastToOrg(actor, orgId, bulletinBody(audience)).andExpect(status().isCreated()));
    }

    // ───────── DB で加盟行を直接変える（離脱・グループ移動の API は他部隊のため） ─────────

    protected void moveTeamToGroup(Long teamId, Long orgId, UUID groupIdOrNull) {
        if (groupIdOrNull == null) {
            em.createQuery("UPDATE TeamOrgMembershipEntity m SET m.groupId = NULL "
                            + "WHERE m.teamId = :t AND m.organizationId = :o")
                    .setParameter("t", teamId).setParameter("o", orgId).executeUpdate();
        } else {
            em.createQuery("UPDATE TeamOrgMembershipEntity m SET m.groupId = :g "
                            + "WHERE m.teamId = :t AND m.organizationId = :o")
                    .setParameter("g", groupIdOrNull).setParameter("t", teamId).setParameter("o", orgId)
                    .executeUpdate();
        }
        flushAndClear();
    }

    /** チームが組織から離脱した状態（加盟行が消えた状態）にする。 */
    protected void leaveOrg(Long teamId, Long orgId) {
        em.createQuery("DELETE FROM TeamOrgMembershipEntity m WHERE m.teamId = :t AND m.organizationId = :o")
                .setParameter("t", teamId).setParameter("o", orgId).executeUpdate();
        flushAndClear();
    }

    protected void deleteGroup(UUID groupId) {
        softDeleteGroup(groupId);
        flushAndClear();
    }

    // ───────── 表示の観測 ─────────

    protected List<Long> teamNoticeIds(Long viewer, Long teamId) {
        flushAndClear();
        TeamDashboardResponse response = dashboardService.getTeamDashboard(viewer, teamId, "WEEK");
        assertThat(response.getTeamNotices()).as("チームのお知らせウィジェットが見えている").isNotNull();
        return response.getTeamNotices().stream()
                .map(m -> ((Number) m.get("id")).longValue())
                .toList();
    }

    protected List<Long> orgNoticeIds(Long viewer, Long orgId) {
        flushAndClear();
        OrgDashboardResponse response = dashboardService.getOrgDashboard(viewer, orgId, "WEEK");
        assertThat(response.getOrgNotices()).as("組織のお知らせウィジェットが見えている").isNotNull();
        return response.getOrgNotices().stream()
                .map(m -> ((Number) m.get("id")).longValue())
                .toList();
    }

    /** T1〜T4 のダッシュボードのうち、告知 feedId が表示されているチーム名の集合を返す。 */
    protected List<String> teamsShowing(long feedId) {
        java.util.ArrayList<String> shown = new java.util.ArrayList<>();
        if (teamNoticeIds(U1, t1.getId()).contains(feedId)) {
            shown.add("T1");
        }
        if (teamNoticeIds(U2, t2.getId()).contains(feedId)) {
            shown.add("T2");
        }
        if (teamNoticeIds(U3, t3.getId()).contains(feedId)) {
            shown.add("T3");
        }
        if (teamNoticeIds(U4, t4.getId()).contains(feedId)) {
            shown.add("T4");
        }
        return shown;
    }
}
