package com.mannschaft.app.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.notification.entity.NotificationPreferenceEntity;
import com.mannschaft.app.notification.repository.NotificationPreferenceRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** NotificationPreferenceController#listPreferences / #updatePreference の未保存既定値契約。 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class NotificationPreferenceDefaultsContractIT extends AbstractMySqlIntegrationTest {
    private static final String PATH = "/api/v1/notification-preferences";
    private static final long MEMBER = 936411L;
    private static final long ADMIN = 936412L;
    private static final long OTHER = 936413L;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private NotificationPreferenceRepository preferences;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @Autowired private TeamOrgMembershipRepository teamOrgMemberships;
    @PersistenceContext private EntityManager em;

    @Test
    @DisplayName("保存0の新規メンバーに所属TEAM/直接ORG既定ONを返し、親組織/他人は含めずGET前後DB不変")
    void newMemberSeesActualAffiliationsWithoutDatabaseWrites() throws Exception {
        long team = team("新規メンバーのチーム");
        long directOrg = organization("直接所属組織");
        long parentOrg = organization("チームだけが所属する組織");
        membership(MEMBER, ScopeType.TEAM, team);
        membership(MEMBER, ScopeType.ORGANIZATION, directOrg);
        teamOrgMemberships.saveAndFlush(NotificationPreferenceDefaultsFixture.teamOrganization(team, parentOrg));
        preferences.saveAndFlush(row(OTHER, "TEAM", team, false));
        long before = preferences.count();

        JsonNode data = list(MEMBER);

        assertThat(keys(data)).containsExactlyInAnyOrder("TEAM:" + team, "ORGANIZATION:" + directOrg);
        for (JsonNode entry : data) {
            assertThat(entry.path("userId").asLong()).isEqualTo(MEMBER);
            assertThat(entry.path("isEnabled").asBoolean()).isTrue();
            assertThat(entry.path("id").isNull() || entry.path("id").isMissingNode()).isTrue();
            assertThat(entry.path("audit").isNull() || entry.path("audit").isMissingNode()).isTrue();
        }
        em.flush();
        em.clear();
        assertThat(preferences.count()).isEqualTo(before);
        assertThat(preferences.findByUserId(MEMBER)).isEmpty();
        assertThat(preferences.findByUserId(OTHER)).singleElement().satisfies(r -> assertThat(r.getIsEnabled()).isFalse());
    }

    @Test
    @DisplayName("user_rolesのみの管理者にも既定ON、membershipsとの重複と複数所属をtype/idで一意にする")
    void administratorRolesAndMembershipsAreMergedOnce() throws Exception {
        long team = team("管理チーム");
        long secondTeam = team("もう一つの所属チーム");
        long org = organization("管理組織");
        MembershipTestHelper.insertActiveUser(em, ADMIN);
        MembershipTestHelper.insertUserRole(em, ADMIN, "ADMIN", team, null);
        MembershipTestHelper.insertUserRole(em, ADMIN, "ADMIN", null, org);
        membership(ADMIN, ScopeType.TEAM, team);
        membership(ADMIN, ScopeType.TEAM, secondTeam);

        JsonNode data = list(ADMIN);

        assertThat(keys(data)).containsExactlyInAnyOrder("TEAM:" + team, "TEAM:" + secondTeam, "ORGANIZATION:" + org);
        for (JsonNode entry : data) {
            assertThat(entry.path("isEnabled").asBoolean()).isTrue();
        }
        assertThat(preferences.findByUserId(ADMIN)).isEmpty();
    }

    @Test
    @DisplayName("保存OFFを優先して未保存所属を補完し、既定ONからPUT OFFを保存して次GETへ反映する")
    void savedOffAndNewOptOutSurviveNextGet() throws Exception {
        long team = team("保存OFFチーム");
        long org = organization("未保存組織");
        membership(MEMBER, ScopeType.TEAM, team);
        membership(MEMBER, ScopeType.ORGANIZATION, org);
        preferences.saveAndFlush(row(MEMBER, "TEAM", team, false));

        JsonNode before = list(MEMBER);
        assertThat(keys(before)).containsExactlyInAnyOrder("TEAM:" + team, "ORGANIZATION:" + org);
        assertThat(entry(before, "TEAM", team).path("isEnabled").asBoolean()).isFalse();
        assertThat(entry(before, "ORGANIZATION", org).path("isEnabled").asBoolean()).isTrue();

        mockMvc.perform(put(PATH).with(user(String.valueOf(MEMBER))).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("scopeType", "ORGANIZATION", "scopeId", org, "isEnabled", false))))
                .andExpect(status().isOk());
        em.flush();
        em.clear();

        JsonNode after = list(MEMBER);
        assertThat(keys(after)).containsExactlyInAnyOrder("TEAM:" + team, "ORGANIZATION:" + org);
        assertThat(entry(after, "ORGANIZATION", org).path("isEnabled").asBoolean()).isFalse();
        assertThat(preferences.findByUserId(MEMBER)).hasSize(2);
    }

    @Test
    @DisplayName("所属なし保存なしは空、SYSTEM_ADMINも全DBチームを既定値として列挙しない")
    void unaffiliatedAndSystemAdministratorHaveNoGlobalDefaults() throws Exception {
        team("無関係チーム");
        organization("無関係組織");
        assertThat(keys(list(MEMBER))).isEmpty();
        MembershipTestHelper.insertActiveUser(em, ADMIN);
        MembershipTestHelper.insertUserRole(em, ADMIN, "SYSTEM_ADMIN", null, null);
        em.flush();
        assertThat(keys(list(ADMIN))).isEmpty();
        assertThat(preferences.findByUserId(ADMIN)).isEmpty();
    }

    @Test
    @DisplayName("他tenant/脱退後の残置行は実名を漏らさず、論理削除scopeのdefaultを作らない")
    void inaccessibleAndDeletedScopesNeverExposeNamesOrDefaults() throws Exception {
        long foreign = team("他tenantの秘密チーム");
        long departed = team("脱退済みの秘密チーム");
        long deleted = team("削除済みの秘密チーム");
        long active = team("現役所属チーム");
        membership(OTHER, ScopeType.TEAM, foreign);
        membership(MEMBER, ScopeType.TEAM, departed);
        membership(MEMBER, ScopeType.TEAM, deleted);
        membership(MEMBER, ScopeType.TEAM, active);
        em.createNativeQuery("UPDATE memberships SET left_at=NOW(), leave_reason='SELF', "
                        + "left_trigger='SELF', left_by=:uid WHERE user_id=:uid AND scope_type='TEAM' AND scope_id=:sid")
                .setParameter("uid", MEMBER).setParameter("sid", departed).executeUpdate();
        em.createNativeQuery("UPDATE teams SET deleted_at=NOW() WHERE id=:id").setParameter("id", deleted).executeUpdate();
        preferences.saveAndFlush(row(MEMBER, "TEAM", foreign, false));
        preferences.saveAndFlush(row(MEMBER, "TEAM", departed, false));
        em.clear();

        JsonNode data = list(MEMBER);

        assertThat(keys(data)).containsExactlyInAnyOrder("TEAM:" + foreign, "TEAM:" + departed, "TEAM:" + active);
        assertThat(entry(data, "TEAM", foreign).path("scopeName").asText()).isEqualTo("不明なチーム");
        assertThat(entry(data, "TEAM", departed).path("scopeName").asText()).isEqualTo("不明なチーム");
        assertThat(data.toString()).doesNotContain("他tenantの秘密チーム", "脱退済みの秘密チーム", "削除済みの秘密チーム");
        mockMvc.perform(put(PATH).with(user(String.valueOf(MEMBER))).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("scopeType", "TEAM", "scopeId", foreign, "isEnabled", true))))
                .andExpect(status().isForbidden());
        assertThat(preferences.findByUserIdAndScopeTypeAndScopeId(MEMBER, "TEAM", foreign).orElseThrow().getIsEnabled()).isFalse();
    }

    @Test
    @DisplayName("未保存所属1件と20件の一覧SQL本数が一定で、全scopeに実名と既定ONがある")
    void defaultsDoNotIntroduceNPlusOneQueries() throws Exception {
        membership(MEMBER, ScopeType.TEAM, team("少数所属"));
        for (int i = 0; i < 20; i++) {
            membership(ADMIN, ScopeType.TEAM, team("多数所属" + i));
        }
        em.flush();
        em.clear();
        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        JsonNode small = list(MEMBER);
        long smallCount = stats.getPrepareStatementCount();
        stats.clear();
        JsonNode large = list(ADMIN);
        long largeCount = stats.getPrepareStatementCount();
        assertThat(small.size()).isEqualTo(1);
        assertThat(large.size()).isEqualTo(20);
        for (JsonNode entry : large) {
            assertThat(entry.path("isEnabled").asBoolean()).isTrue();
            assertThat(entry.path("scopeName").asText()).startsWith("多数所属");
        }
        assertThat(smallCount).isPositive();
        assertThat(largeCount).as("未保存所属1件=%d本、20件=%d本", smallCount, largeCount).isEqualTo(smallCount);
    }

    private JsonNode list(long userId) throws Exception {
        em.flush();
        String body = mockMvc.perform(get(PATH).with(user(String.valueOf(userId))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    private List<String> keys(JsonNode data) {
        List<String> keys = new ArrayList<>();
        for (JsonNode entry : data) {
            keys.add(entry.path("scope").path("scopeType").asText() + ":" + entry.path("scope").path("scopeId").asLong());
        }
        return keys;
    }

    private JsonNode entry(JsonNode data, String type, long id) {
        for (JsonNode entry : data) {
            if (type.equals(entry.path("scope").path("scopeType").asText()) && entry.path("scope").path("scopeId").asLong() == id) {
                return entry;
            }
        }
        throw new AssertionError("レスポンスに必要な所属がない: " + type + ":" + id);
    }

    private void membership(long uid, ScopeType type, long id) {
        MembershipTestHelper.insertActiveUser(em, uid);
        MembershipTestHelper.insertMembership(em, uid, type, id, RoleKind.MEMBER);
    }

    private NotificationPreferenceEntity row(long uid, String type, long id, boolean enabled) {
        return NotificationPreferenceEntity.builder().userId(uid).scopeType(type).scopeId(id).isEnabled(enabled).build();
    }

    private long team(String name) {
        return teams.saveAndFlush(NotificationPreferenceDefaultsFixture.team(name)).getId();
    }

    private long organization(String name) {
        return organizations.saveAndFlush(NotificationPreferenceDefaultsFixture.organization(name)).getId();
    }

    /** この契約に必要な最小の所属先データを生成し、保存は呼び出し元のRepositoryに任せる。 */
    private static final class NotificationPreferenceDefaultsFixture {
        private NotificationPreferenceDefaultsFixture() {
        }

        static TeamEntity team(String name) {
            return TeamEntity.builder().name(name).slug(slug())
                    .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(true).build();
        }

        static OrganizationEntity organization(String name) {
            return OrganizationEntity.builder().name(name).slug(slug()).orgType(OrganizationEntity.OrgType.OTHER)
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(true).build();
        }

        static TeamOrgMembershipEntity teamOrganization(long teamId, long organizationId) {
            return TeamOrgMembershipEntity.builder().teamId(teamId).organizationId(organizationId)
                    .status(TeamOrgMembershipEntity.Status.ACTIVE).invitedAt(LocalDateTime.now()).build();
        }

        private static String slug() {
            return "notif-def-" + UUID.randomUUID().toString().substring(0, 16);
        }
    }
}
