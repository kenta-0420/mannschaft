package com.mannschaft.app.social.announcement.audience;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import com.mannschaft.app.team.entity.TeamEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 6-A — 宛先の保存（target_group_ids・include_unassigned・target_audience・スナップショット）。
 *
 * <p>AC-H26（グループ宛てでは push の有無にかかわらず常にスナップショット行を作り、フィード削除で CASCADE）と
 * AC-H17（送信時の宛先の記録はグループの改名・削除で変わらない）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 6-A 宛先の保存（AC-H26・H17）")
class BroadcastAudiencePersistenceIT extends AbstractBroadcastAudienceIT {

    private OrganizationEntity orgX;
    private OrgTeamGroupEntity g1;
    private OrgTeamGroupEntity g2;
    private OrgTeamGroupEntity g3;
    private TeamEntity t1;
    private TeamEntity t2;
    private TeamEntity t3;
    private TeamEntity t0;

    @BeforeEach
    void setUp() {
        orgX = newOrg(true);
        g1 = newGroup(orgX.getId(), "G1", 0);
        g2 = newGroup(orgX.getId(), "G2", 1);
        g3 = newGroup(orgX.getId(), "G3", 2);
        newGroup(orgX.getId(), "G4", 3);
        t1 = activeTeam(orgX.getId(), g1.getId(), "T1");
        t2 = activeTeam(orgX.getId(), g2.getId(), "T2");
        t3 = activeTeam(orgX.getId(), g3.getId(), "T3");
        t0 = activeTeam(orgX.getId(), null, "T0");
        seedOrgPerson(XA, orgX.getId(), "ADMIN");
        seedOrgPerson(XM, orgX.getId(), "MEMBER");
        seedOrgPerson(XO, orgX.getId(), "MEMBER");
        flushAndClear();
    }

    @Test
    @DisplayName("AC-H26: 「G2 以前」で送ると、展開したグループ ID と (グループ, チーム) のスナップショットが保存される")
    void rangeUpToG2_savesGroupsAndSnapshots() throws Exception {
        long feedId = feedIdOf(broadcastToOrg(XA, orgX.getId(),
                bulletinBody(Map.of("targetGroupRange", range(null, g2.getId())))).andExpect(status().isCreated()));

        assertThat(savedTargetGroupIds(feedId)).containsExactly(g1.getId().toString(), g2.getId().toString());
        assertThat(savedIncludeUnassigned(feedId)).isFalse();
        assertThat(savedTargetTeamIds(feedId)).as("グループ宛ては target_team_ids を使わない").isNull();
        assertThat(snapshotPairs(feedId)).containsExactlyInAnyOrder(
                pair(g1.getId(), t1.getId()), pair(g2.getId(), t2.getId()));
    }

    @Test
    @DisplayName("AC-H26: push を出さない送信者（MEMBER）・チャネル（掲示板）でもスナップショットは作られる")
    void memberBulletin_alsoSavesSnapshots() throws Exception {
        long feedId = feedIdOf(broadcastToOrg(XM, orgX.getId(),
                bulletinBody(Map.of("targetGroupRange", range(g3.getId(), null)))).andExpect(status().isCreated()));

        assertThat(snapshotPairs(feedId)).containsExactly(pair(g3.getId(), t3.getId()));
    }

    @Test
    @DisplayName("AC-H26: 未分類を含めても、スナップショットはグループ単位の行だけ（未分類チームは include_unassigned で表す）")
    void includeUnassigned_isFlagNotSnapshot() throws Exception {
        long feedId = feedIdOf(broadcastToOrg(XA, orgX.getId(), bulletinBody(Map.of(
                        "targetGroupIds", ids(g1.getId()),
                        "includeUnassigned", true)))
                .andExpect(status().isCreated()));

        assertThat(savedIncludeUnassigned(feedId)).isTrue();
        assertThat(snapshotPairs(feedId)).containsExactly(pair(g1.getId(), t1.getId()));
        assertThat(snapshotPairs(feedId)).noneMatch(p -> p.endsWith(":" + t0.getId()));
    }

    @Test
    @DisplayName("AC-H26: フィードを削除するとスナップショット行も CASCADE で消える")
    void deletingFeed_cascadesSnapshots() throws Exception {
        long feedId = feedIdOf(broadcastToOrg(XA, orgX.getId(),
                bulletinBody(Map.of("targetGroupIds", ids(g1.getId(), g2.getId())))).andExpect(status().isCreated()));
        assertThat(snapshotPairs(feedId)).hasSize(2);
        flushAndClear();

        em.createNativeQuery("DELETE FROM announcement_feeds WHERE id = :id").setParameter("id", feedId).executeUpdate();

        assertThat(snapshotPairs(feedId)).isEmpty();
    }

    @Test
    @DisplayName("AC-H17: target_audience に「G2 以前（送信時 2 チーム）」が記録され、G2 を改名・削除しても変わらない")
    void targetAudience_isFrozenAtSend() throws Exception {
        long feedId = feedIdOf(broadcastToOrg(XA, orgX.getId(),
                        bulletinBody(Map.of("targetGroupRange", range(null, g2.getId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.targetAudience.mode").value("GROUPS"))
                .andExpect(jsonPath("$.data.targetAudience.teamCount").value(2))
                .andExpect(jsonPath("$.data.targetGroupIds.length()").value(2)));

        JsonNode atSend = savedTargetAudience(feedId);
        assertThat(atSend.path("mode").asText()).isEqualTo("GROUPS");
        assertThat(atSend.path("teamCount").asInt()).isEqualTo(2);
        assertThat(atSend.path("range").hasNonNull("fromGroupId")).as("「以前」は開始を持たない").isFalse();
        assertThat(atSend.path("range").path("toGroupId").asText()).isEqualTo(g2.getId().toString());
        assertThat(atSend.path("range").path("toGroupName").asText()).isEqualTo("G2");
        assertThat(atSend.path("groups").findValuesAsText("name")).containsExactly("G1", "G2");

        renameGroup(g2.getId(), "G2改");
        softDeleteGroup(g2.getId());
        flushAndClear();

        assertThat(savedTargetAudience(feedId)).isEqualTo(atSend);
        assertThat(snapshotPairs(feedId)).contains(pair(g2.getId(), t2.getId()));
    }

    @Test
    @DisplayName("「チームを選ぶ」では target_audience に TEAMS と件数を残し、スナップショットは作らない")
    void teamsMode_recordsAudienceWithoutSnapshots() throws Exception {
        long feedId = feedIdOf(broadcastToOrg(XA, orgX.getId(),
                bulletinBody(Map.of("targetTeamIds", List.of(t1.getId(), t3.getId())))).andExpect(status().isCreated()));

        JsonNode audience = savedTargetAudience(feedId);
        assertThat(audience.path("mode").asText()).isEqualTo("TEAMS");
        assertThat(audience.path("teamCount").asInt()).isEqualTo(2);
        assertThat(savedTargetGroupIds(feedId)).isNull();
        assertThat(snapshotPairs(feedId)).isEmpty();
    }

    @Test
    @DisplayName("「すべてのチーム」は従来どおり宛先の列をすべて空のまま保存する（後方互換）")
    void allTeams_keepsColumnsEmpty() throws Exception {
        long feedId = feedIdOf(broadcastToOrg(XA, orgX.getId(), bulletinBody(Map.of())).andExpect(status().isCreated()));

        assertThat(savedTargetTeamIds(feedId)).isNull();
        assertThat(savedTargetGroupIds(feedId)).isNull();
        assertThat(savedTargetAudience(feedId)).isNull();
        assertThat(savedIncludeUnassigned(feedId)).isFalse();
        assertThat(snapshotPairs(feedId)).isEmpty();
    }
}
