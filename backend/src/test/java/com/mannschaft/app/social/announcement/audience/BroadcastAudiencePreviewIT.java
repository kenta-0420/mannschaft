package com.mannschaft.app.social.announcement.audience;

import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 6-A — 宛先プレビュー {@code POST /api/v1/organizations/{orgId}/broadcast/audience-preview}。
 *
 * <p>AC-H15（件数・サンプル・pushEnabled）と AC-G122（認可は broadcast 先頭の checkMembership と同じ契約。
 * 非メンバー・他組織・存在しない orgId は 403 {@code COMMON_002}、MEMBER 以上は 200）。
 * 非メンバーに 400（BROADCAST_006 など）を返すと、他組織のグループ ID の存在が漏れるため、認可を検証より先に行う。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 6-A 宛先プレビュー（AC-H15・G122）")
class BroadcastAudiencePreviewIT extends AbstractBroadcastAudienceIT {

    private OrganizationEntity orgX;
    private OrganizationEntity orgY;
    private OrgTeamGroupEntity g1;
    private OrgTeamGroupEntity g2;
    private OrgTeamGroupEntity gY;

    @BeforeEach
    void setUp() {
        orgX = newOrg(true);
        orgY = newOrg(true);
        g1 = newGroup(orgX.getId(), "G1", 0);
        g2 = newGroup(orgX.getId(), "G2", 1);
        OrgTeamGroupEntity g3 = newGroup(orgX.getId(), "G3", 2);
        OrgTeamGroupEntity g4 = newGroup(orgX.getId(), "G4", 3);
        gY = newGroup(orgY.getId(), "Yのグループ", 0);
        activeTeam(orgX.getId(), g1.getId(), "T1");
        activeTeam(orgX.getId(), g2.getId(), "T2");
        activeTeam(orgX.getId(), g3.getId(), "T3");
        activeTeam(orgX.getId(), g4.getId(), "T4");
        activeTeam(orgX.getId(), null, "T0");
        TeamEntity pending = newTeam("TP");
        affiliate(pending.getId(), orgX.getId(), TeamOrgMembershipEntity.Status.PENDING, g1.getId());
        seedOrgPerson(XA, orgX.getId(), "ADMIN");
        seedOrgPerson(XD2, orgX.getId(), "DEPUTY_ADMIN");
        seedOrgPerson(XM, orgX.getId(), "MEMBER");
        seedOrgPerson(XO, orgX.getId(), "MEMBER");
        seedOrgPerson(YA, orgY.getId(), "ADMIN");
        seedUserOnly(N);
        flushAndClear();
    }

    @Nested
    @DisplayName("AC-H15 件数・サンプル・pushEnabled")
    class Counts {

        @Test
        @DisplayName("XA が「G2 以前」をアンケートで指定すると、2 チーム（T1・T2）とグループ G1・G2、pushEnabled=true")
        void adminRangeUpToG2() throws Exception {
            preview(XA, orgX.getId(), body("SURVEY", Map.of("targetGroupRange", range(null, g2.getId()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.resolvedTeamCount").value(2))
                    .andExpect(jsonPath("$.data.sampleTeams[*].name", contains("T1", "T2")))
                    .andExpect(jsonPath("$.data.sampleTeams[0].slug").isNotEmpty())
                    .andExpect(jsonPath("$.data.groups[*].name", contains("G1", "G2")))
                    // 直属メンバーは送信者本人を除いた組織メンバー（XD2・XM・XO）
                    .andExpect(jsonPath("$.data.directMemberCount").value(3))
                    .andExpect(jsonPath("$.data.pushEnabled").value(true))
                    .andExpect(jsonPath("$.data.warnings", hasSize(0)));
        }

        @Test
        @DisplayName("直属メンバー数は告知対象ロールに合わせる: MEMBERS_AND_ABOVE は純 SUPPORTER を数えず、SUPPORTERS_AND_ABOVE は数える")
        void directMemberCountFollowsTargetRole() throws Exception {
            Long xs = 940601060L;
            seedUserOnly(xs);
            com.mannschaft.app.support.test.MembershipTestHelper.insertMembership(
                    em, xs, com.mannschaft.app.membership.domain.ScopeType.ORGANIZATION, orgX.getId(),
                    com.mannschaft.app.membership.domain.RoleKind.SUPPORTER);
            flushAndClear();
            java.util.Map<String, Object> members = new java.util.LinkedHashMap<>();
            members.put("targetGroupIds", ids(g1.getId()));
            members.put("targetRole", "MEMBERS_AND_ABOVE");
            preview(XA, orgX.getId(), bulletinBody(members))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.directMemberCount").value(3)); // XD2・XM・XO
            members.put("targetRole", "SUPPORTERS_AND_ABOVE");
            preview(XA, orgX.getId(), bulletinBody(members))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.directMemberCount").value(4)); // ＋XS
        }

        @Test
        @DisplayName("XM（MEMBER）・XD2（MANAGE_CONTENT なし）が呼ぶと pushEnabled=false")
        void memberAndDeputyWithoutPermission_noPush() throws Exception {
            String json = body("SURVEY", Map.of("targetGroupRange", range(null, g2.getId())));
            preview(XM, orgX.getId(), json).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.resolvedTeamCount").value(2))
                    .andExpect(jsonPath("$.data.pushEnabled").value(false));
            preview(XD2, orgX.getId(), json).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pushEnabled").value(false));
        }

        @Test
        @DisplayName("push を出さないチャネル（掲示板）では ADMIN でも pushEnabled=false")
        void bulletin_noPush() throws Exception {
            preview(XA, orgX.getId(), bulletinBody(Map.of("targetGroupIds", ids(g1.getId()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.resolvedTeamCount").value(1))
                    .andExpect(jsonPath("$.data.pushEnabled").value(false));
        }

        @Test
        @DisplayName("G1 と未分類を選ぶと T1・T0（PENDING の TP は入らない）")
        void groupAndUnassigned_excludesPending() throws Exception {
            preview(XA, orgX.getId(), bulletinBody(Map.of(
                            "targetGroupIds", ids(g1.getId()),
                            "includeUnassigned", true)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.resolvedTeamCount").value(2))
                    .andExpect(jsonPath("$.data.sampleTeams[*].name", contains("T1", "T0")));
        }

        @Test
        @DisplayName("宛先を絞らない（すべてのチーム）なら ACTIVE の全チーム数を返し、directMemberCount は 0")
        void allTeams() throws Exception {
            preview(XA, orgX.getId(), bulletinBody(Map.of()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.resolvedTeamCount").value(5))
                    .andExpect(jsonPath("$.data.directMemberCount").value(0))
                    .andExpect(jsonPath("$.data.groups", hasSize(0)));
        }

        @Test
        @DisplayName("対象0件でもプレビューは 200 で 0 件を返す（画面が「次へ」を止めるため。送信は BROADCAST_009）")
        void emptyResult_isOkWithZero() throws Exception {
            OrganizationEntity orgW = newOrg(true);
            OrgTeamGroupEntity empty = newGroup(orgW.getId(), "空", 0);
            Long wa = 940601099L;
            seedOrgPerson(wa, orgW.getId(), "ADMIN");
            flushAndClear();
            preview(wa, orgW.getId(), bulletinBody(Map.of("targetGroupIds", ids(empty.getId()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.resolvedTeamCount").value(0))
                    .andExpect(jsonPath("$.data.directMemberCount").value(0))
                    .andExpect(jsonPath("$.data.sampleTeams", hasSize(0)));
        }

        @Test
        @DisplayName("検証はプレビューでも broadcast と同じ（範囲の逆転は 400 BROADCAST_008、他組織のグループは 400 BROADCAST_006）")
        void validationMatchesBroadcast() throws Exception {
            preview(XA, orgX.getId(), bulletinBody(Map.of("targetGroupRange", range(g2.getId(), g1.getId()))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("BROADCAST_008"));
            preview(XA, orgX.getId(), bulletinBody(Map.of("targetGroupIds", ids(gY.getId()))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("BROADCAST_006"));
        }

        @Test
        @DisplayName("プレビューは何も書き込まない（フィード・スナップショットが増えない）")
        void previewDoesNotWrite() throws Exception {
            long before = feedCount(orgX.getId());
            preview(XA, orgX.getId(), bulletinBody(Map.of("targetGroupIds", ids(g1.getId())))).andExpect(status().isOk());
            assertThat(feedCount(orgX.getId())).isEqualTo(before);
            long snapshots = ((Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM announcement_feed_group_snapshots s "
                                    + "JOIN announcement_feeds f ON f.id = s.feed_id "
                                    + "WHERE f.scope_type = 'ORGANIZATION' AND f.scope_id = :o")
                    .setParameter("o", orgX.getId()).getSingleResult()).longValue();
            assertThat(snapshots).isZero();
        }
    }

    @Nested
    @DisplayName("AC-G122 認可と存在オラクル")
    class Authorization {

        private void assertForbidden(ResultActions result) throws Exception {
            result.andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他組織の ADMIN（YA）は 403 COMMON_002")
        void otherOrgAdmin_is403() throws Exception {
            assertForbidden(preview(YA, orgX.getId(), bulletinBody(Map.of())));
        }

        @Test
        @DisplayName("どの組織にも属さない N は 403 COMMON_002")
        void nonMember_is403() throws Exception {
            assertForbidden(preview(N, orgX.getId(), bulletinBody(Map.of())));
        }

        @Test
        @DisplayName("存在しない orgId は 403 COMMON_002（存在する他組織と同じ応答）")
        void unknownOrg_is403() throws Exception {
            assertForbidden(preview(XA, 987_654_321L, bulletinBody(Map.of())));
        }

        @Test
        @DisplayName("非メンバーが他組織のグループ ID・範囲を送っても 400 ではなく 403（グループの存在を漏らさない）")
        void nonMemberWithForeignGroups_is403Not400() throws Exception {
            assertForbidden(preview(YA, orgX.getId(), bulletinBody(Map.of("targetGroupIds", ids(g1.getId())))));
            assertForbidden(preview(YA, orgX.getId(),
                    bulletinBody(Map.of("targetGroupRange", range(g2.getId(), g1.getId())))));
            assertForbidden(preview(N, orgX.getId(), bulletinBody(Map.of("targetGroupIds", ids(gY.getId())))));
        }

        @Test
        @DisplayName("組織の MEMBER（XM）は 200")
        void member_is200() throws Exception {
            preview(XM, orgX.getId(), bulletinBody(Map.of())).andExpect(status().isOk());
        }

        @Test
        @DisplayName("他組織への告知（broadcast）も同じく 403 COMMON_002（プレビューだけ別の応答にしない）")
        void broadcastHasSameContract() throws Exception {
            assertForbidden(broadcastToOrg(YA, orgX.getId(), bulletinBody(Map.of("targetGroupIds", ids(g1.getId())))));
        }
    }
}
