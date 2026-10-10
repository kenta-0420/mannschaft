package com.mannschaft.app.social.announcement.audience;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import com.mannschaft.app.support.test.MembershipTestHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 6-C — グループ宛て告知の表示判定（設計書 §8.2。試練・red）。
 *
 * <p>生存グループは動的、削除済みグループは送信時スナップショット、未分類は動的で、OR を取る。
 * 告知は 6-A の送信 API、表示は DashboardService（チーム・組織）の実経路で観測する。
 * 加盟行の変更（グループ移動・離脱）は、他部隊の API が未実装のため DB を直接変える。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 6-C グループ宛て告知の表示判定")
class AnnouncementAudienceDisplayIT extends AbstractAudienceDisplayIT {

    @BeforeEach
    void setUp() {
        setUpOrgX();
    }

    // ───────── H01〜H03: 動的判定（生存グループ・範囲・未分類） ─────────

    @Test
    @DisplayName("AC-H01: 「G2 以前」は T1・T2 に表示され、T3・T4 には出ない。XO は組織ダッシュボードで見られる")
    void h01_rangeUpToG2_showsOnT1T2_andOrgDashboardForXO() throws Exception {
        long feed = send(Map.of("targetGroupRange", range(null, g2.getId())));

        assertThat(teamsShowing(feed)).containsExactly("T1", "T2");
        assertThat(orgNoticeIds(XO, orgX.getId())).as("直属メンバー XO は組織ダッシュボードで見られる").contains(feed);
    }

    @Test
    @DisplayName("AC-H02: 「G3 以降」は T3 だけ、「G2〜G3」は T2・T3 に表示される")
    void h02_openEndedAndClosedRanges() throws Exception {
        long fromG3 = send(Map.of("targetGroupRange", range(g3.getId(), null)));
        long g2ToG3 = send(Map.of("targetGroupRange", range(g2.getId(), g3.getId())));

        assertThat(teamsShowing(fromG3)).as("G3 以降").containsExactly("T3");
        assertThat(teamsShowing(g2ToG3)).as("G2〜G3").containsExactly("T2", "T3");
    }

    @Test
    @DisplayName("AC-H03: 個別チェックと「未分類を含める」の組み合わせ。未分類を含めないときは T4 に出ない")
    void h03_individualGroupsAndIncludeUnassigned() throws Exception {
        long g1PlusUnassigned = send(Map.of("targetGroupIds", ids(g1.getId()), "includeUnassigned", true));
        long g1AndG3 = send(Map.of("targetGroupIds", ids(g1.getId(), g3.getId())));

        assertThat(teamsShowing(g1PlusUnassigned)).as("G1 と未分類").containsExactly("T1", "T4");
        assertThat(teamsShowing(g1AndG3)).as("未分類を含めない G1・G3").containsExactly("T1", "T3");
    }

    @Test
    @DisplayName("AC-H03: 削除済みグループを指す加盟行も「未分類」として動的に判定される（NULL または削除済みを指す）")
    void h03_teamPointingAtDeletedGroupIsUnassigned() throws Exception {
        long feed = send(Map.of("targetGroupIds", ids(g1.getId()), "includeUnassigned", true));

        deleteGroup(g2.getId());

        assertThat(teamsShowing(feed)).as("T2 の加盟行は削除済み G2 を指すため未分類として出る").containsExactly("T1", "T2", "T4");
    }

    // ───────── H04: 送信後の移動 ─────────

    @Test
    @DisplayName("AC-H04（表示）: 送信後にグループを移ったチームには、移動後の所属で表示される（グループが生きている間）")
    void h04_movedTeamsFollowCurrentGroupWhileGroupAlive() throws Exception {
        long feed = send(Map.of("targetGroupRange", range(null, g2.getId())));

        moveTeamToGroup(t3.getId(), orgX.getId(), g2.getId());
        moveTeamToGroup(t2.getId(), orgX.getId(), g3.getId());

        assertThat(teamsShowing(feed)).as("T3 は G2 へ移ったので出る。T2 は G3 へ移ったので出ない")
                .containsExactly("T1", "T3");
    }

    // ───────── H20・H23・H24・H25: 削除後のスナップショット ─────────

    @Test
    @DisplayName("AC-H20: G2 を削除した後も、スナップショットにより T1・T2 に表示され続ける。T3・T4 には出ない")
    void h20_afterGroupDeletion_snapshotKeepsShowingOnT1T2() throws Exception {
        long feed = send(Map.of("targetGroupRange", range(null, g2.getId())));

        deleteGroup(g2.getId());

        assertThat(teamsShowing(feed)).containsExactly("T1", "T2");
        assertThat(orgNoticeIds(XO, orgX.getId())).as("組織側の表示は変わらない").contains(feed);
    }

    @Test
    @DisplayName("AC-H23: 送信後に T2 を G3 へ移し T3 を G2 へ移す。G2 の削除前は現在の所属で、削除後は送信時の対象で判定される")
    void h23_moveAndDeleteCombination() throws Exception {
        long feed = send(Map.of("targetGroupRange", range(null, g2.getId())));

        moveTeamToGroup(t2.getId(), orgX.getId(), g3.getId());
        moveTeamToGroup(t3.getId(), orgX.getId(), g2.getId());
        assertThat(teamsShowing(feed)).as("削除前は動的: T1(G1)・T3(G2)").containsExactly("T1", "T3");

        deleteGroup(g2.getId());

        assertThat(teamsShowing(feed)).as("削除後: T2 は送信時に G2 の対象だったので出る。T3 はスナップショットに無いので出ない")
                .containsExactly("T1", "T2");
    }

    @Test
    @DisplayName("AC-H24: 「G1 と G2」で送信後に G2 だけを削除すると、G1 は動的、G2 はスナップショットで切り替わる")
    void h24_perGroupSwitchBetweenDynamicAndSnapshot() throws Exception {
        long feed = send(Map.of("targetGroupIds", ids(g1.getId(), g2.getId())));

        deleteGroup(g2.getId());
        assertThat(teamsShowing(feed)).as("T1 は動的（今 G1）、T2 はスナップショット").containsExactly("T1", "T2");

        moveTeamToGroup(t1.getId(), orgX.getId(), g3.getId());
        assertThat(teamsShowing(feed)).as("T1 が G1 から出ると動的判定が外れる。T2 のスナップショット判定は変わらない")
                .containsExactly("T2");
    }

    @Test
    @DisplayName("AC-H25: スナップショットに入っていた T2 が G2 の削除後に組織Xを離脱すると、T2 には出ない（今も ACTIVE を要求）")
    void h25_snapshotJudgementStillRequiresActiveAffiliation() throws Exception {
        long feed = send(Map.of("targetGroupRange", range(null, g2.getId())));

        deleteGroup(g2.getId());
        assertThat(teamsShowing(feed)).as("対照: 離脱前は T2 に出る").containsExactly("T1", "T2");

        leaveOrg(t2.getId(), orgX.getId());

        assertThat(teamsShowing(feed)).containsExactly("T1");
    }

    // ───────── H29: 回帰（チームを選ぶ） ─────────

    @Test
    @DisplayName("AC-H29: 「チームを選ぶ」の表示判定は従来どおり。選んだ T1 にだけ出て、T1 がグループを移っても出続ける")
    void h29_teamSelectionRegression() throws Exception {
        long feed = send(Map.of("targetTeamIds", List.of(t1.getId())));
        assertThat(teamsShowing(feed)).containsExactly("T1");

        moveTeamToGroup(t1.getId(), orgX.getId(), g3.getId());
        assertThat(teamsShowing(feed)).as("グループを移っても T1 に出続け、T3 には出ない").containsExactly("T1");

        deleteGroup(g1.getId());
        assertThat(teamsShowing(feed)).as("グループの削除でも変わらない").containsExactly("T1");
    }

    // ───────── E01・G111・H16 ─────────

    @Test
    @DisplayName("AC-E01（表示）: T1 が組織Xから離脱すると X のグループ宛て告知は T1 に出ず、組織Yの告知は出続ける")
    void e01_leavingOrgHidesThatOrgsGroupFeeds_butOtherOrgContinues() throws Exception {
        OrganizationEntity orgY = newOrg(true);
        OrgTeamGroupEntity gy = newGroup(orgY.getId(), "GY", 0);
        affiliate(t1.getId(), orgY.getId(), com.mannschaft.app.team.entity.TeamOrgMembershipEntity.Status.ACTIVE,
                gy.getId());
        seedOrgPerson(YA, orgY.getId(), "ADMIN");
        MembershipTestHelper.insertMembership(em, U1, ScopeType.ORGANIZATION, orgY.getId(), RoleKind.MEMBER);
        flushAndClear();

        long feedX = send(Map.of("targetGroupIds", ids(g1.getId())));
        long feedY = sendAs(YA, orgY.getId(), Map.of("targetGroupIds", ids(gy.getId())));
        assertThat(teamNoticeIds(U1, t1.getId())).as("対照: 離脱前は両方出る").contains(feedX, feedY);

        leaveOrg(t1.getId(), orgX.getId());

        List<Long> seen = teamNoticeIds(U1, t1.getId());
        assertThat(seen).as("X の告知は出ない").doesNotContain(feedX);
        assertThat(seen).as("Y の告知は出続ける").contains(feedY);
    }

    @Test
    @DisplayName("AC-E01（表示・全チーム宛て）: 組織ロールを持たない T1 のメンバーは、T1 が X から離脱すると X の全チーム宛て告知も見えなくなり、Y の全チーム宛て告知は見え続ける")
    void e01_leavingOrgHidesAllTeamsFeedsToo() throws Exception {
        Long teamOnlyViewer = 940603031L;
        OrganizationEntity orgY = newOrg(true);
        OrgTeamGroupEntity gy = newGroup(orgY.getId(), "GY", 0);
        affiliate(t1.getId(), orgY.getId(), com.mannschaft.app.team.entity.TeamOrgMembershipEntity.Status.ACTIVE,
                gy.getId());
        seedOrgPerson(YA, orgY.getId(), "ADMIN");
        MembershipTestHelper.insertActiveUser(em, teamOnlyViewer);
        MembershipTestHelper.insertMembership(em, teamOnlyViewer, ScopeType.TEAM, t1.getId(), RoleKind.MEMBER);
        flushAndClear();

        long feedX = send(Map.of());
        long feedY = sendAs(YA, orgY.getId(), Map.of());
        assertThat(teamNoticeIds(teamOnlyViewer, t1.getId())).as("対照: 離脱前は両方出る").contains(feedX, feedY);

        leaveOrg(t1.getId(), orgX.getId());

        List<Long> seen = teamNoticeIds(teamOnlyViewer, t1.getId());
        assertThat(seen).as("X の全チーム宛て告知は出ない").doesNotContain(feedX);
        assertThat(seen).as("Y の全チーム宛て告知は出続ける").contains(feedY);
    }

    @Test
    @DisplayName("AC-G111（表示）: グループ機能を off にしても、送信済みのグループ宛て告知は表示を続ける")
    void g111_sentGroupFeedsKeepShowingAfterGroupsDisabled() throws Exception {
        long feed = send(Map.of("targetGroupIds", ids(g1.getId())));
        assertThat(teamsShowing(feed)).as("対照: off にする前").containsExactly("T1");

        em.createNativeQuery("UPDATE organizations SET team_groups_enabled = FALSE WHERE id = :id")
                .setParameter("id", orgX.getId()).executeUpdate();
        flushAndClear();

        assertThat(teamsShowing(feed)).containsExactly("T1");
    }

    @Test
    @DisplayName("AC-H16: 宛先外チーム T3 のメンバーも、グループ宛て告知の元の掲示板スレッドを URL 直打ちで閲覧できる")
    void h16_nonTargetTeamMemberCanOpenOriginalThread() throws Exception {
        ResultActions sent = broadcastToOrg(XA, orgX.getId(),
                bulletinBody(Map.of("targetGroupRange", range(null, g1.getId())))).andExpect(status().isCreated());
        JsonNode data = objectMapper.readTree(sent.andReturn().getResponse().getContentAsString()).path("data");
        long feed = data.path("announcementFeedId").asLong();
        long threadId = data.path("contentId").asLong();
        flushAndClear();

        assertThat(teamsShowing(feed)).as("前提: T3 のダッシュボードには出ない").containsExactly("T1");
        mockMvc.perform(get("/api/v1/organizations/{orgId}/bulletin/threads/{threadId}", orgX.getId(), threadId)
                        .with(user(U3.toString())))
                .andExpect(status().isOk());
    }
}
