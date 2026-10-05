package com.mannschaft.app.social.announcement.audience;

import com.mannschaft.app.dashboard.dto.TeamDashboardResponse;
import com.mannschaft.app.dashboard.service.DashboardService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 6-A — グループ宛ての告知がチームのダッシュボードへ漏れないこと（Codex 検分 高1）。
 *
 * <p>グループ宛ての告知は target_team_ids が NULL になる。従来の表示判定は NULL を「全チーム宛て」と読むため、
 * G1 宛ての告知が G2 のチームにも出てしまう。部隊 6-C の表示判定（AnnouncementAudienceMatcher）により、
 * 宛先の G1 のチームには出て、宛先外のチームには漏れない。組織側の一覧には出る。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 6-A グループ宛ての告知は宛先外のチームのダッシュボードに漏れない")
class BroadcastAudienceDashboardLeakIT extends AbstractBroadcastAudienceIT {

    private static final Long U1 = 940601051L;
    private static final Long U2 = 940601052L;

    @Autowired
    private DashboardService dashboardService;

    private OrganizationEntity orgX;
    private OrgTeamGroupEntity g1;
    private TeamEntity t1;
    private TeamEntity t2;

    @BeforeEach
    void setUp() {
        orgX = newOrg(true);
        g1 = newGroup(orgX.getId(), "G1", 0);
        OrgTeamGroupEntity g2 = newGroup(orgX.getId(), "G2", 1);
        t1 = activeTeam(orgX.getId(), g1.getId(), "T1");
        t2 = activeTeam(orgX.getId(), g2.getId(), "T2");
        seedOrgPerson(XA, orgX.getId(), "ADMIN");
        seedOrgPerson(U1, orgX.getId(), "MEMBER");
        MembershipTestHelper.insertMembership(em, U1, ScopeType.TEAM, t1.getId(), RoleKind.MEMBER);
        seedOrgPerson(U2, orgX.getId(), "MEMBER");
        MembershipTestHelper.insertMembership(em, U2, ScopeType.TEAM, t2.getId(), RoleKind.MEMBER);
        flushAndClear();
    }

    @Test
    @DisplayName("グループ宛て（G1・G1 以前＋未分類）は宛先の G1 のチームにだけ出て、全チーム宛て・チームを選ぶは従来どおり出る")
    void groupTargetedFeedsAreHiddenFromTeams() throws Exception {
        long all = feedIdOf(broadcastToOrg(XA, orgX.getId(), bulletinBody(Map.of())).andExpect(status().isCreated()));
        long toT1 = feedIdOf(broadcastToOrg(XA, orgX.getId(),
                bulletinBody(Map.of("targetTeamIds", List.of(t1.getId())))).andExpect(status().isCreated()));
        long toG1 = feedIdOf(broadcastToOrg(XA, orgX.getId(),
                bulletinBody(Map.of("targetGroupIds", ids(g1.getId())))).andExpect(status().isCreated()));
        long rangeG1 = feedIdOf(broadcastToOrg(XA, orgX.getId(),
                bulletinBody(Map.of("targetGroupRange", range(null, g1.getId()), "includeUnassigned", true)))
                .andExpect(status().isCreated()));
        flushAndClear();

        List<Long> seenByT1 = teamNoticeIds(U1, t1.getId());
        List<Long> seenByT2 = teamNoticeIds(U2, t2.getId());

        assertThat(seenByT1).as("対照: 全チーム宛て・T1 を選んだ告知は T1 に出る").contains(all, toT1);
        assertThat(seenByT2).as("対照: 全チーム宛ては T2 にも出るが、T1 を選んだ告知は出ない").contains(all)
                .doesNotContain(toT1);
        assertThat(seenByT2).as("G1 宛ては G2 のチームに漏れない").doesNotContain(toG1, rangeG1);
        assertThat(seenByT1).as("6-C: 宛先の G1 のチームには出る").contains(toG1, rangeG1);
    }

    @Test
    @DisplayName("グループ宛ての告知も、組織側の一覧には出る")
    void groupTargetedFeedIsListedOnOrganizationSide() throws Exception {
        long toG1 = feedIdOf(broadcastToOrg(XA, orgX.getId(),
                bulletinBody(Map.of("targetGroupIds", ids(g1.getId())))).andExpect(status().isCreated()));
        flushAndClear();

        String json = mockMvc.perform(get("/api/v1/organizations/{orgId}/announcements", orgX.getId())
                        .with(user(XA.toString())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Long> listed = objectMapper.readTree(json).path("data").findValues("id").stream()
                .map(n -> n.asLong()).toList();
        assertThat(listed).contains(toG1);
    }

    private List<Long> teamNoticeIds(Long viewer, Long teamId) {
        TeamDashboardResponse response = dashboardService.getTeamDashboard(viewer, teamId, "WEEK");
        assertThat(response.getTeamNotices()).as("チームのお知らせウィジェットが見えている").isNotNull();
        return response.getTeamNotices().stream()
                .map(m -> ((Number) m.get("id")).longValue())
                .toList();
    }
}
