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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 6-A — 告知の宛先の検証（BROADCAST_002・006〜012）。
 *
 * <p>AC-H05・H06・H12・H14b・K05・K07。前提は設計書 §16 H の配置（組織X に G1〜G4 をこの順で置き、
 * 各グループにチーム T1〜T4、未分類チーム T0）。他組織 Y、グループ機能 off の組織 Z を併置する。
 * 検証の失敗はコンテンツもフィードも作らない（件数で確かめる）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 6-A 告知の宛先の検証（AC-H05・H06・H12・H14b・K05・K07）")
class BroadcastAudienceValidationIT extends AbstractBroadcastAudienceIT {

    private OrganizationEntity orgX;
    private OrganizationEntity orgY;
    private OrganizationEntity orgZ;
    private OrgTeamGroupEntity g1;
    private OrgTeamGroupEntity g2;
    private OrgTeamGroupEntity g3;
    private OrgTeamGroupEntity gY;
    private OrgTeamGroupEntity gZ;
    private TeamEntity t1;
    private TeamEntity t2;
    private TeamEntity tY;

    @BeforeEach
    void setUp() {
        orgX = newOrg(true);
        orgY = newOrg(true);
        orgZ = newOrg(false);
        g1 = newGroup(orgX.getId(), "G1", 0);
        g2 = newGroup(orgX.getId(), "G2", 1);
        g3 = newGroup(orgX.getId(), "G3", 2);
        newGroup(orgX.getId(), "G4", 3);
        gY = newGroup(orgY.getId(), "Yのグループ", 0);
        gZ = newGroup(orgZ.getId(), "Zのグループ", 0);
        t1 = activeTeam(orgX.getId(), g1.getId(), "T1");
        t2 = activeTeam(orgX.getId(), g2.getId(), "T2");
        activeTeam(orgX.getId(), g3.getId(), "T3");
        activeTeam(orgX.getId(), null, "T0");
        tY = activeTeam(orgY.getId(), gY.getId(), "TY");
        seedOrgPerson(XA, orgX.getId(), "ADMIN");
        seedOrgPerson(XO, orgX.getId(), "MEMBER");
        seedOrgPerson(YA, orgY.getId(), "ADMIN");
        seedOrgPerson(ZA, orgZ.getId(), "ADMIN");
        flushAndClear();
    }

    private void assertRejected(ResultActions result, String code, Long orgId, long feedsBefore) throws Exception {
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(code));
        assertThat(feedCount(orgId)).as("検証で落ちた告知はフィードを作らない").isEqualTo(feedsBefore);
    }

    @Nested
    @DisplayName("AC-H05 範囲の向き")
    class RangeOrder {

        @Test
        @DisplayName("開始 G3・終了 G2（開始が終了より後ろ）は 400 BROADCAST_008")
        void reversedRange_is008() throws Exception {
            long before = feedCount(orgX.getId());
            assertRejected(broadcastToOrg(XA, orgX.getId(),
                    bulletinBody(Map.of("targetGroupRange", range(g3.getId(), g2.getId())))),
                    "BROADCAST_008", orgX.getId(), before);
        }

        @Test
        @DisplayName("開始と終了が同じグループなら成立する（両端を含む）")
        void sameEnds_isAccepted() throws Exception {
            broadcastToOrg(XA, orgX.getId(), bulletinBody(Map.of("targetGroupRange", range(g2.getId(), g2.getId()))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("開始・終了とも null の範囲は 400 BROADCAST_008（範囲が成り立たない）")
        void emptyRange_is008() throws Exception {
            long before = feedCount(orgX.getId());
            assertRejected(broadcastToOrg(XA, orgX.getId(), bulletinBody(Map.of("targetGroupRange", range(null, null)))),
                    "BROADCAST_008", orgX.getId(), before);
        }
    }

    @Nested
    @DisplayName("AC-H06 対象0件")
    class EmptyAudience {

        @Test
        @DisplayName("送信者のほかに直属メンバーがいない組織で、空のグループだけを選ぶと 400 BROADCAST_009")
        void emptyGroupWithoutDirectMembers_is009() throws Exception {
            OrganizationEntity orgW = newOrg(true);
            OrgTeamGroupEntity empty = newGroup(orgW.getId(), "空のグループ", 0);
            Long wa = 940601099L;
            seedOrgPerson(wa, orgW.getId(), "ADMIN");
            flushAndClear();

            long before = feedCount(orgW.getId());
            assertRejected(broadcastToOrg(wa, orgW.getId(), bulletinBody(Map.of("targetGroupIds", ids(empty.getId())))),
                    "BROADCAST_009", orgW.getId(), before);
        }

        @Test
        @DisplayName("空のグループでも、直属メンバー（XO）がいれば送れる")
        void emptyGroupWithDirectMember_isAccepted() throws Exception {
            OrgTeamGroupEntity empty = newGroup(orgX.getId(), "空のグループ", 9);
            flushAndClear();
            broadcastToOrg(XA, orgX.getId(), bulletinBody(Map.of("targetGroupIds", ids(empty.getId()))))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("AC-H12 グループ指定の各拒否")
    class GroupRejections {

        @Test
        @DisplayName("他組織 Y のグループ ID は 400 BROADCAST_006")
        void otherOrgGroup_is006() throws Exception {
            long before = feedCount(orgX.getId());
            assertRejected(broadcastToOrg(XA, orgX.getId(), bulletinBody(Map.of("targetGroupIds", ids(gY.getId())))),
                    "BROADCAST_006", orgX.getId(), before);
        }

        @Test
        @DisplayName("削除済みグループは 400 BROADCAST_006（他組織と同じ応答で、存在を区別しない）")
        void deletedGroup_is006() throws Exception {
            softDeleteGroup(g2.getId());
            flushAndClear();
            long before = feedCount(orgX.getId());
            assertRejected(broadcastToOrg(XA, orgX.getId(), bulletinBody(Map.of("targetGroupIds", ids(g2.getId())))),
                    "BROADCAST_006", orgX.getId(), before);
        }

        @Test
        @DisplayName("範囲の端に他組織のグループを置いても 400 BROADCAST_006")
        void otherOrgGroupAsRangeEnd_is006() throws Exception {
            long before = feedCount(orgX.getId());
            assertRejected(broadcastToOrg(XA, orgX.getId(),
                    bulletinBody(Map.of("targetGroupRange", range(g1.getId(), gY.getId())))),
                    "BROADCAST_006", orgX.getId(), before);
        }

        @Test
        @DisplayName("グループ機能 off の組織 Z でグループを指定すると 400 BROADCAST_007（未分類だけの指定も同じ）")
        void groupsDisabled_is007() throws Exception {
            long before = feedCount(orgZ.getId());
            assertRejected(broadcastToOrg(ZA, orgZ.getId(), bulletinBody(Map.of("targetGroupIds", ids(gZ.getId())))),
                    "BROADCAST_007", orgZ.getId(), before);
            assertRejected(broadcastToOrg(ZA, orgZ.getId(), bulletinBody(Map.of("includeUnassigned", true))),
                    "BROADCAST_007", orgZ.getId(), before);
        }

        @Test
        @DisplayName("TEAM スコープの告知でグループを指定すると 400 BROADCAST_012")
        void teamScope_is012() throws Exception {
            Long teamAdmin = 940601098L;
            seedTeamPerson(teamAdmin, t1.getId(), "ADMIN");
            flushAndClear();
            mockMvc.perform(post(TEAM_BROADCAST, t1.getId()).with(user(teamAdmin.toString()))
                            .contentType("application/json")
                            .content(bulletinBody(Map.of("targetGroupIds", ids(g1.getId())))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("BROADCAST_012"));
        }

        @Test
        @DisplayName("targetTeamIds とグループ指定を併用すると 400 BROADCAST_011")
        void teamsAndGroups_is011() throws Exception {
            long before = feedCount(orgX.getId());
            assertRejected(broadcastToOrg(XA, orgX.getId(), bulletinBody(Map.of(
                            "targetTeamIds", List.of(t1.getId()),
                            "targetGroupIds", ids(g1.getId())))),
                    "BROADCAST_011", orgX.getId(), before);
            assertRejected(broadcastToOrg(XA, orgX.getId(), bulletinBody(Map.of(
                            "targetTeamIds", List.of(t1.getId()),
                            "includeUnassigned", true))),
                    "BROADCAST_011", orgX.getId(), before);
        }
    }

    @Nested
    @DisplayName("AC-K05 他組織のチーム・グループを混ぜられない")
    class CrossTenant {

        @Test
        @DisplayName("YA が組織 Y の告知で組織 X のチームを指定すると 400 BROADCAST_002")
        void otherOrgTeam_is002() throws Exception {
            long before = feedCount(orgY.getId());
            assertRejected(broadcastToOrg(YA, orgY.getId(), bulletinBody(Map.of("targetTeamIds", List.of(t1.getId())))),
                    "BROADCAST_002", orgY.getId(), before);
        }

        @Test
        @DisplayName("自組織のチームに他組織のチームを1件混ぜても 400 BROADCAST_002（部分的に通さない）")
        void mixedTeams_is002() throws Exception {
            long before = feedCount(orgY.getId());
            assertRejected(broadcastToOrg(YA, orgY.getId(),
                            bulletinBody(Map.of("targetTeamIds", List.of(tY.getId(), t1.getId())))),
                    "BROADCAST_002", orgY.getId(), before);
        }

        @Test
        @DisplayName("YA が組織 Y の告知で組織 X のグループを指定すると 400 BROADCAST_006")
        void otherOrgGroup_is006() throws Exception {
            long before = feedCount(orgY.getId());
            assertRejected(broadcastToOrg(YA, orgY.getId(),
                            bulletinBody(Map.of("targetGroupIds", ids(gY.getId(), g1.getId())))),
                    "BROADCAST_006", orgY.getId(), before);
        }
    }

    @Nested
    @DisplayName("AC-K07 宛先候補は ACTIVE の加盟だけ")
    class ActiveOnly {

        @Test
        @DisplayName("user_roles では組織 X に紐づくが加盟していないチームは 400 BROADCAST_002")
        void userRolesOnlyTeam_is002() throws Exception {
            TeamEntity tu = newTeam("TU");
            Long officer = 940601097L;
            seedUserOnly(officer);
            com.mannschaft.app.support.test.MembershipTestHelper.insertUserRole(
                    em, officer, "DEPUTY_ADMIN", tu.getId(), orgX.getId());
            flushAndClear();
            long before = feedCount(orgX.getId());
            assertRejected(broadcastToOrg(XA, orgX.getId(), bulletinBody(Map.of("targetTeamIds", List.of(tu.getId())))),
                    "BROADCAST_002", orgX.getId(), before);
        }

        @Test
        @DisplayName("PENDING（申請中・招待中）のチームは 400 BROADCAST_002")
        void pendingTeam_is002() throws Exception {
            TeamEntity tp = newTeam("TP");
            affiliate(tp.getId(), orgX.getId(), TeamOrgMembershipEntity.Status.PENDING, null);
            flushAndClear();
            long before = feedCount(orgX.getId());
            assertRejected(broadcastToOrg(XA, orgX.getId(), bulletinBody(Map.of("targetTeamIds", List.of(tp.getId())))),
                    "BROADCAST_002", orgX.getId(), before);
        }

        @Test
        @DisplayName("離脱済み（加盟行が消えた）チームは 400 BROADCAST_002")
        void leftTeam_is002() throws Exception {
            TeamEntity tl = activeTeam(orgX.getId(), null, "TL");
            em.createNativeQuery("DELETE FROM team_org_memberships WHERE team_id = :t AND organization_id = :o")
                    .setParameter("t", tl.getId()).setParameter("o", orgX.getId()).executeUpdate();
            flushAndClear();
            long before = feedCount(orgX.getId());
            assertRejected(broadcastToOrg(XA, orgX.getId(), bulletinBody(Map.of("targetTeamIds", List.of(tl.getId())))),
                    "BROADCAST_002", orgX.getId(), before);
        }

        @Test
        @DisplayName("PENDING のチームはグループ展開でも宛先に入らない")
        void pendingTeamIsNotExpandedFromGroup() throws Exception {
            TeamEntity tp = newTeam("TP");
            affiliate(tp.getId(), orgX.getId(), TeamOrgMembershipEntity.Status.PENDING, g1.getId());
            flushAndClear();
            long feedId = feedIdOf(broadcastToOrg(XA, orgX.getId(),
                    bulletinBody(Map.of("targetGroupIds", ids(g1.getId())))).andExpect(status().isCreated()));
            assertThat(snapshotPairs(feedId)).containsExactly(pair(g1.getId(), t1.getId()));
        }
    }

    @Nested
    @DisplayName("明示の空配列・テンプレートだけの指定は「すべてのチーム」に倒さない（Codex 検分 高2・高3）")
    class NoSilentFallbackToAll {

        @Test
        @DisplayName("targetTeamIds=[] は 400 BROADCAST_009（送信もプレビューも）")
        void emptyTeamIds_is009() throws Exception {
            long before = feedCount(orgX.getId());
            assertRejected(broadcastToOrg(XA, orgX.getId(), bulletinBody(Map.of("targetTeamIds", List.of()))),
                    "BROADCAST_009", orgX.getId(), before);
            preview(XA, orgX.getId(), bulletinBody(Map.of("targetTeamIds", List.of())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("BROADCAST_009"));
        }

        @Test
        @DisplayName("targetGroupIds=[] だけ（範囲も未分類も無い）は 400 BROADCAST_009（送信もプレビューも）")
        void emptyGroupIds_is009() throws Exception {
            long before = feedCount(orgX.getId());
            assertRejected(broadcastToOrg(XA, orgX.getId(), bulletinBody(Map.of("targetGroupIds", List.of()))),
                    "BROADCAST_009", orgX.getId(), before);
            preview(XA, orgX.getId(), bulletinBody(Map.of("targetGroupIds", List.of())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("BROADCAST_009"));
        }

        @Test
        @DisplayName("targetGroupIds=[] でも範囲を指定していれば、範囲で送れる")
        void emptyGroupIdsWithRange_isAccepted() throws Exception {
            long feedId = feedIdOf(broadcastToOrg(XA, orgX.getId(), bulletinBody(Map.of(
                            "targetGroupIds", List.of(),
                            "targetGroupRange", range(g1.getId(), g1.getId()))))
                    .andExpect(status().isCreated()));
            assertThat(snapshotPairs(feedId)).containsExactly(pair(g1.getId(), t1.getId()));
        }

        @Test
        @DisplayName("targetTeamIds=[] とグループ指定の併用は 400 BROADCAST_011")
        void emptyTeamIdsWithGroups_is011() throws Exception {
            long before = feedCount(orgX.getId());
            assertRejected(broadcastToOrg(XA, orgX.getId(), bulletinBody(Map.of(
                            "targetTeamIds", List.of(),
                            "targetGroupIds", ids(g1.getId())))),
                    "BROADCAST_011", orgX.getId(), before);
        }

        @Test
        @DisplayName("宛先を明示せず templateId だけの送信・プレビューは 400 COMMON_001（テンプレートの解決は 6-B まで未実装）")
        void templateOnly_isRejected() throws Exception {
            long before = feedCount(orgX.getId());
            assertRejected(broadcastToOrg(XA, orgX.getId(), bulletinBody(Map.of("templateId", 123_456L))),
                    "COMMON_001", orgX.getId(), before);
            preview(XA, orgX.getId(), bulletinBody(Map.of("templateId", 123_456L)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        }

        @Test
        @DisplayName("templateId だけの送信でも、非メンバーには 400 ではなく 403（認可が先）")
        void templateOnlyByNonMember_is403() throws Exception {
            broadcastToOrg(YA, orgX.getId(), bulletinBody(Map.of("templateId", 123_456L)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }
    }

    @Nested
    @DisplayName("アーカイブ済み・論理削除済みのチームは候補にも展開にも入らない（Codex 検分 中1）")
    class ArchivedTeams {

        @Test
        @DisplayName("アーカイブ済み・論理削除済みのチームを選ぶと 400 BROADCAST_002")
        void archivedOrDeletedTeam_is002() throws Exception {
            TeamEntity archived = activeTeam(orgX.getId(), null, "TA");
            archived.archive();
            teamRepository.saveAndFlush(archived);
            TeamEntity deleted = activeTeam(orgX.getId(), null, "TD");
            deleted.softDelete();
            teamRepository.saveAndFlush(deleted);
            flushAndClear();

            long before = feedCount(orgX.getId());
            assertRejected(broadcastToOrg(XA, orgX.getId(),
                            bulletinBody(Map.of("targetTeamIds", List.of(archived.getId())))),
                    "BROADCAST_002", orgX.getId(), before);
            assertRejected(broadcastToOrg(XA, orgX.getId(),
                            bulletinBody(Map.of("targetTeamIds", List.of(deleted.getId())))),
                    "BROADCAST_002", orgX.getId(), before);
        }

        @Test
        @DisplayName("グループ・未分類の展開、プレビュー、スナップショットからも除かれる")
        void archivedOrDeletedTeamIsNotExpanded() throws Exception {
            TeamEntity archivedInG1 = activeTeam(orgX.getId(), g1.getId(), "TA1");
            archivedInG1.archive();
            teamRepository.saveAndFlush(archivedInG1);
            TeamEntity deletedUnassigned = activeTeam(orgX.getId(), null, "TD0");
            deletedUnassigned.softDelete();
            teamRepository.saveAndFlush(deletedUnassigned);
            flushAndClear();

            String json = bulletinBody(Map.of("targetGroupIds", ids(g1.getId()), "includeUnassigned", true));
            preview(XA, orgX.getId(), json)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.resolvedTeamCount").value(2)); // T1 と T0 だけ
            long feedId = feedIdOf(broadcastToOrg(XA, orgX.getId(), json).andExpect(status().isCreated()));
            assertThat(snapshotPairs(feedId)).containsExactly(pair(g1.getId(), t1.getId()));
            assertThat(savedTargetAudience(feedId).path("teamCount").asInt()).isEqualTo(2);

            preview(XA, orgX.getId(), bulletinBody(Map.of()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.resolvedTeamCount").value(4)); // T1・T2・T3・T0
        }
    }

    @Nested
    @DisplayName("AC-H14b 上限と重複")
    class LimitsAndDuplicates {

        @Test
        @DisplayName("「チームを選ぶ」で 501 件は 400 BROADCAST_010（加盟の照合より先に弾く）")
        void teams501_is010() throws Exception {
            List<Long> ids = new ArrayList<>(LongStream.rangeClosed(1, 501).map(i -> 980_000_000L + i).boxed().toList());
            long before = feedCount(orgX.getId());
            assertRejected(broadcastToOrg(XA, orgX.getId(), bulletinBody(Map.of("targetTeamIds", ids))),
                    "BROADCAST_010", orgX.getId(), before);
        }

        @Test
        @DisplayName("「チームを選ぶ」で 500 件は成功し、グループ経由なら 501 チームでも成功する")
        void teams500_andGroupOf501_succeed() throws Exception {
            OrgTeamGroupEntity big = newGroup(orgX.getId(), "大きなグループ", 10);
            List<Long> bigTeams = activeTeams(orgX.getId(), big.getId(), 501);
            flushAndClear();

            long teamFeed = feedIdOf(broadcastToOrg(XA, orgX.getId(),
                    bulletinBody(Map.of("targetTeamIds", bigTeams.subList(0, 500)))).andExpect(status().isCreated()));
            assertThat(savedTargetTeamIds(teamFeed)).hasSize(500);

            broadcastToOrg(XA, orgX.getId(), bulletinBody(Map.of("targetTeamIds", bigTeams)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("BROADCAST_010"));

            long groupFeed = feedIdOf(broadcastToOrg(XA, orgX.getId(),
                    bulletinBody(Map.of("targetGroupIds", ids(big.getId())))).andExpect(status().isCreated()));
            assertThat(snapshotPairs(groupFeed)).hasSize(501);
        }

        @Test
        @DisplayName("同じチーム・同じグループを重ねて指定しても、保存は1件ずつ（重複排除）")
        void duplicates_areCollapsed() throws Exception {
            long teamFeed = feedIdOf(broadcastToOrg(XA, orgX.getId(),
                    bulletinBody(Map.of("targetTeamIds", List.of(t1.getId(), t1.getId(), t2.getId()))))
                    .andExpect(status().isCreated()));
            assertThat(savedTargetTeamIds(teamFeed)).containsExactly(t1.getId(), t2.getId());

            long groupFeed = feedIdOf(broadcastToOrg(XA, orgX.getId(), bulletinBody(Map.of(
                            "targetGroupIds", ids(g1.getId(), g1.getId()),
                            "targetGroupRange", range(g1.getId(), g1.getId()))))
                    .andExpect(status().isCreated()));
            assertThat(savedTargetGroupIds(groupFeed)).containsExactly(g1.getId().toString());
            assertThat(snapshotPairs(groupFeed)).containsExactly(pair(g1.getId(), t1.getId()));
        }
    }
}
