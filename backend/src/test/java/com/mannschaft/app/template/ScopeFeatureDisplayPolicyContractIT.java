package com.mannschaft.app.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.template.repository.ScopeFeatureDisplayPolicyRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.3.1 AC-1/2/3/6/7/9の実MySQL・実Security契約試練。
 * 初回はController未実装の404を観測し、200を要求する実アサーションのredを確認する。
 * commit/rollbackとAFTER_COMMITの観測は別の非Transactional試練で担う。
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ScopeFeatureDisplayPolicyContractIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @Autowired private MembershipRepository memberships;
    @Autowired private RoleRepository roles;
    @Autowired private UserRoleRepository userRoles;
    @Autowired private ScopeFeatureDisplayPolicyRepository policies;
    @Autowired private EntityManager entityManager;
    private Long admin;
    private Long member;
    private Long supporter;
    private Long deputy;
    private Long outsider;
    private Long systemAdmin;
    private Long teamId;
    private Long orgId;
    private String teamPath;
    private String orgPath;

    @BeforeEach
    void fixture() {
        String[] names = {"SYSTEM_ADMIN", "ADMIN", "DEPUTY_ADMIN", "MEMBER", "SUPPORTER", "GUEST"};
        for (int i = 0; i < names.length; i++) {
            if (roles.findByName(names[i]).isEmpty()) {
                roles.saveAndFlush(RoleEntity.builder().name(names[i]).displayName(names[i])
                        .priority(i + 1).isSystem(true).build());
            }
        }
        admin = users.saveAndFlush(FeatureDisplayPolicyTestFixture.user()).getId();
        member = users.saveAndFlush(FeatureDisplayPolicyTestFixture.user()).getId();
        supporter = users.saveAndFlush(FeatureDisplayPolicyTestFixture.user()).getId();
        deputy = users.saveAndFlush(FeatureDisplayPolicyTestFixture.user()).getId();
        outsider = users.saveAndFlush(FeatureDisplayPolicyTestFixture.user()).getId();
        systemAdmin = users.saveAndFlush(FeatureDisplayPolicyTestFixture.user()).getId();
        var team = teams.saveAndFlush(FeatureDisplayPolicyTestFixture.team());
        teamId = team.getId();
        var org = organizations.saveAndFlush(FeatureDisplayPolicyTestFixture.organization());
        orgId = org.getId();
        teamPath = "/api/v1/teams/" + team.getSlug() + "/feature-display-policies";
        orgPath = "/api/v1/organizations/" + org.getSlug() + "/feature-display-policies";
        for (ScopeType scope : List.of(ScopeType.TEAM, ScopeType.ORGANIZATION)) {
            Long id = scope == ScopeType.TEAM ? teamId : orgId;
            for (Long actor : List.of(admin, member, deputy)) {
                membership(actor, scope, id, RoleKind.MEMBER);
            }
            membership(supporter, scope, id, RoleKind.SUPPORTER);
            role(admin, scope, id, "ADMIN");
            role(deputy, scope, id, "DEPUTY_ADMIN");
        }
        role(systemAdmin, null, null, "SYSTEM_ADMIN");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void AC2_未保存は既定trueで初期行を作らない(boolean organization) throws Exception {
        mvc.perform(get(path(organization)).with(user(member.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.features[?(@.featureKey == 'schedule')].memberVisible").value(true))
                .andExpect(jsonPath("$.data.features[?(@.featureKey == 'schedule')].supporterVisible").value(true));
        assertThat(policies.findByScopeTypeAndScopeId(scope(organization), id(organization))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "false,false,true", "false,true,false", "false,true,true",
            "true,false,false", "true,false,true", "true,true,false", "true,true,true"})
    void AC1_四通りを保存再取得できる(boolean organization, boolean memberVisible, boolean supporterVisible) throws Exception {
        mvc.perform(put(path(organization)).with(user(admin.toString())).contentType("application/json")
                        .content(body(memberVisible, supporterVisible)))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        mvc.perform(get(path(organization)).with(user(supporter.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.features[?(@.featureKey == 'schedule')].memberVisible").value(memberVisible))
                .andExpect(jsonPath("$.data.features[?(@.featureKey == 'schedule')].supporterVisible").value(supporterVisible));
        var rows = policies.findByScopeTypeAndScopeId(scope(organization), id(organization));
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().isMemberVisible()).isEqualTo(memberVisible);
        assertThat(rows.getFirst().isSupporterVisible()).isEqualTo(supporterVisible);
        assertThat(rows.getFirst().getId().version()).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void AC6_実ロール境界と運営例外(boolean organization) throws Exception {
        for (Long actor : List.of(admin, deputy, member, supporter, systemAdmin)) {
            mvc.perform(get(path(organization)).with(user(actor.toString()))).andExpect(status().isOk());
        }
        for (Long actor : List.of(deputy, member, supporter, outsider)) {
            mvc.perform(put(path(organization)).with(user(actor.toString())).contentType("application/json")
                            .content("{\"features\":[]}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(put(path(organization)).with(user(systemAdmin.toString())).contentType("application/json")
                        .content(body(false, false))).andExpect(status().isOk());
        mvc.perform(get(path(organization)).with(user(outsider.toString()))).andExpect(status().isForbidden());
        mvc.perform(get(path(organization))).andExpect(status().isUnauthorized());
        mvc.perform(put(path(organization)).contentType("application/json").content("{\"features\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"features\":null}", "{\"features\":[null]}",
            "{\"features\":[{}]}", "{\"features\":[{\"featureKey\":\"schedule\",\"memberVisible\":true}]}",
            "{\"features\":[{\"featureKey\":\"schedule\",\"memberVisible\":null,\"supporterVisible\":true}]}",
            "{\"features\":[{\"featureKey\":\"unknown\",\"memberVisible\":true,\"supporterVisible\":true}]}",
            "{\"features\":[{\"featureKey\":\"\",\"memberVisible\":true,\"supporterVisible\":true}]}"})
    void AC3_不正本文は400で書込なし(String content) throws Exception {
        for (String path : List.of(teamPath, orgPath)) {
            mvc.perform(put(path).with(user(admin.toString())).contentType("application/json").content(content))
                    .andExpect(status().isBadRequest());
        }
        assertThat(policies.findByScopeTypeAndScopeId(FeatureDisplayScope.TEAM, teamId)).isEmpty();
        assertThat(policies.findByScopeTypeAndScopeId(FeatureDisplayScope.ORGANIZATION, orgId)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void AC3_空は全列不変と全カタログちょうどは成功する(boolean organization) throws Exception {
        mvc.perform(put(path(organization)).with(user(admin.toString())).contentType("application/json")
                .content(body(false, true))).andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        String before = json.writeValueAsString(policies.findByScopeTypeAndScopeId(scope(organization), id(organization)).getFirst());
        mvc.perform(put(path(organization)).with(user(admin.toString())).contentType("application/json")
                .content("{\"features\":[]}")).andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        String after = json.writeValueAsString(policies.findByScopeTypeAndScopeId(scope(organization), id(organization)).getFirst());
        assertThat(after).isEqualTo(before);
        String response = mvc.perform(get(path(organization)).with(user(member.toString()))).andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(response).get("data");
        mvc.perform(put(path(organization)).with(user(admin.toString())).contentType("application/json")
                .content(data.toString())).andExpect(status().isOk());
        var features = (com.fasterxml.jackson.databind.node.ArrayNode) data.get("features");
        features.add(features.get(0).deepCopy());
        mvc.perform(put(path(organization)).with(user(admin.toString())).contentType("application/json")
                .content(data.toString())).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void AC3_重複と長さ境界と他scope専用キーは400(boolean organization) throws Exception {
        for (String key : List.of("a".repeat(64), "a".repeat(65), organization ? "shifts" : "children")) {
            mvc.perform(put(path(organization)).with(user(admin.toString())).contentType("application/json")
                    .content(body(false, true).replace("schedule", key))).andExpect(status().isBadRequest());
        }
        String item = "{\"featureKey\":\"schedule\",\"memberVisible\":false,\"supporterVisible\":true}";
        mvc.perform(put(path(organization)).with(user(admin.toString())).contentType("application/json")
                .content("{\"features\":[" + item + "," + item + "]}")).andExpect(status().isBadRequest());
        assertThat(policies.findByScopeTypeAndScopeId(scope(organization), id(organization))).isEmpty();
    }

    @Test
    void AC7_同一利用者でも別スコープへ漏れない() throws Exception {
        mvc.perform(put(teamPath).with(user(admin.toString())).contentType("application/json")
                .content(body(false, false))).andExpect(status().isOk());
        mvc.perform(get(orgPath).with(user(member.toString()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.features[?(@.featureKey == 'schedule')].memberVisible").value(true));
        var other = teams.saveAndFlush(FeatureDisplayPolicyTestFixture.team());
        String otherPath = "/api/v1/teams/" + other.getSlug() + "/feature-display-policies";
        mvc.perform(get(otherPath).with(user(admin.toString()))).andExpect(status().isForbidden());
        mvc.perform(put(otherPath).with(user(admin.toString())).contentType("application/json")
                .content(body(false, false))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/teams/does-not-exist/feature-display-policies").with(user(admin.toString())))
                .andExpect(status().isNotFound());
    }

    @Test
    void AC7_親子組織の設定と所属は継承しない() throws Exception {
        var child = organizations.saveAndFlush(FeatureDisplayPolicyTestFixture.organization().toBuilder()
                .parentOrganizationId(orgId).build());
        String childPath = "/api/v1/organizations/" + child.getSlug() + "/feature-display-policies";
        mvc.perform(put(orgPath).with(user(admin.toString())).contentType("application/json")
                .content(body(false, false))).andExpect(status().isOk());
        mvc.perform(get(childPath).with(user(admin.toString()))).andExpect(status().isForbidden());
        mvc.perform(put(childPath).with(user(admin.toString())).contentType("application/json")
                .content(body(false, false))).andExpect(status().isForbidden());
        membership(admin, ScopeType.ORGANIZATION, child.getId(), RoleKind.MEMBER);
        role(admin, ScopeType.ORGANIZATION, child.getId(), "ADMIN");
        mvc.perform(get(childPath).with(user(admin.toString()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.features[?(@.featureKey == 'schedule')].memberVisible").value(true));
        mvc.perform(put(childPath).with(user(admin.toString())).contentType("application/json")
                .content(body(true, false))).andExpect(status().isOk());
        mvc.perform(get(orgPath).with(user(admin.toString()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.features[?(@.featureKey == 'schedule')].memberVisible").value(false));
        mvc.perform(get(childPath).with(user(outsider.toString()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/organizations/does-not-exist/feature-display-policies").with(user(admin.toString())))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void AC7_削除scopeは運営にも読取更新を許可しない(boolean organization) throws Exception {
        if (organization) {
            organizations.findById(orgId).orElseThrow().softDelete();
        } else {
            teams.findById(teamId).orElseThrow().softDelete();
        }
        entityManager.flush();
        entityManager.clear();
        mvc.perform(get(path(organization)).with(user(systemAdmin.toString()))).andExpect(status().isNotFound());
        mvc.perform(put(path(organization)).with(user(systemAdmin.toString())).contentType("application/json")
                .content(body(false, false))).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void AC11_保存キーが増えてもscope全設定の取得は一回(boolean organization) throws Exception {
        String response = mvc.perform(get(path(organization)).with(user(admin.toString())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(response).get("data");
        mvc.perform(put(path(organization)).with(user(admin.toString())).contentType("application/json")
                .content(data.toString())).andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        var statistics = entityManager.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        try {
            statistics.setStatisticsEnabled(true);
            statistics.clear();
            var rows = policies.findByScopeTypeAndScopeId(scope(organization), id(organization));
            assertThat(rows).hasSize(data.get("features").size());
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        } finally {
            statistics.setStatisticsEnabled(enabled);
        }
    }

    private String body(boolean memberVisible, boolean supporterVisible) {
        return "{\"features\":[{\"featureKey\":\"schedule\",\"memberVisible\":" + memberVisible
                + ",\"supporterVisible\":" + supporterVisible + "}]}";
    }
    private String path(boolean organization) { return organization ? orgPath : teamPath; }
    private Long id(boolean organization) { return organization ? orgId : teamId; }
    private FeatureDisplayScope scope(boolean organization) {
        return organization ? FeatureDisplayScope.ORGANIZATION : FeatureDisplayScope.TEAM;
    }
    private void membership(Long actor, ScopeType scope, Long id, RoleKind kind) {
        memberships.saveAndFlush(MembershipEntity.builder().userId(actor).scopeType(scope).scopeId(id).roleKind(kind).build());
    }
    private void role(Long actor, ScopeType scope, Long id, String name) {
        userRoles.saveAndFlush(UserRoleEntity.builder().userId(actor).roleId(roles.findByName(name).orElseThrow().getId())
                .teamId(scope == ScopeType.TEAM ? id : null).organizationId(scope == ScopeType.ORGANIZATION ? id : null)
                .grantedBy(actor).build());
    }
}
