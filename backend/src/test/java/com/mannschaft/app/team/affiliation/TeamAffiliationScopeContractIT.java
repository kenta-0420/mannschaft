package com.mannschaft.app.team.affiliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.support.test.MembershipTestHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 AC-G134（前者）{@code TeamAffiliationScopeContractIT} — チーム側の加盟 API の契約テスト（試練）。
 *
 * <p>「非メンバー 403・越境 404・正当なら成功」を実 Security・実 DB・MockMvc で確かめる。
 * {@code team_org_memberships} / {@code team_org_affiliation_restrictions} は物理削除で管理し、
 * {@code AbstractTenantAwareRepository} を継承しない（§5.1）ため、チーム側から引くクエリが
 * 必ず {@code team_id} を条件に含むことの担保を本テストが兼ねる。</p>
 *
 * <p>権限の外にいる主体はすべて 403（組織の ADMIN・SYSTEM_ADMIN・他チームの ADMIN を含む）。
 * 加盟の ID を総当たりしても、他チームの行・存在しない行は区別できない同じ 404 {@code TEAM_070} になる
 * （存在オラクルを作らない）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 AC-G134 TeamAffiliationScopeContractIT（チーム側の加盟 API の認可契約）")
class TeamAffiliationScopeContractIT extends TeamAffiliationItSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private TeamFx teamA;
    private TeamFx teamB;
    private OrgFx org;
    private long adminA;
    private long adminB;
    private long orgAdmin;
    private long sysAdmin;
    private long outsider;

    @BeforeEach
    void setUp() {
        seedAffiliationPermission();
        teamA = newTeam();
        teamB = newTeam();
        org = newOrg();
        adminA = newUser();
        adminB = newUser();
        orgAdmin = newUser();
        sysAdmin = newUser();
        outsider = newUser();
        makeTeamAdmin(adminA, teamA.id());
        makeTeamAdmin(adminB, teamB.id());
        makeOrgAdmin(orgAdmin, org.id());
        MembershipTestHelper.insertUserRole(em, sysAdmin, "SYSTEM_ADMIN", null, null);
        em.flush();
        em.clear();
    }

    // =====================================================================
    // 401 未認証
    // =====================================================================

    @Test
    @DisplayName("未認証は申請・一覧・取下げのすべてが 401")
    void 未認証は401() throws Exception {
        mockMvc.perform(post("/api/v1/teams/{slug}/org-applications", teamA.slug())
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody(org.slug())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/teams/{slug}/org-applications", teamA.slug()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/teams/{slug}/org-applications/{id}", teamA.slug(), 1))
                .andExpect(status().isUnauthorized());
    }

    // =====================================================================
    // 403 非メンバー（権限の外にいる主体）
    // =====================================================================

    @Test
    @DisplayName("チームの非メンバーは、申請・一覧・取下げのすべてが 403 で、行は作られない")
    void 非メンバーは403() throws Exception {
        assertForbiddenForAllEndpoints(outsider, teamA);
        assertThat(countMemberships(teamA.id(), org.id())).isZero();
    }

    @Test
    @DisplayName("他チームの ADMIN は、越境して自分以外のチームの加盟 API を呼んでも 403")
    void 他チームのADMINは403() throws Exception {
        assertForbiddenForAllEndpoints(adminB, teamA);
        assertForbiddenForAllEndpoints(adminA, teamB);
        assertThat(countMemberships(teamA.id(), org.id())).isZero();
        assertThat(countMemberships(teamB.id(), org.id())).isZero();
    }

    @Test
    @DisplayName("組織の ADMIN は、チームで権限を持たなければ 403（組織側の権限はチーム側の加盟操作を許さない）")
    void 組織ADMINは403() throws Exception {
        assertForbiddenForAllEndpoints(orgAdmin, teamA);
    }

    @Test
    @DisplayName("SYSTEM_ADMIN は、チームの加盟操作者でなければ 403（申請・取下げは SYSTEM_ADMIN ❌）")
    void SYSTEM_ADMINは403() throws Exception {
        assertForbiddenForAllEndpoints(sysAdmin, teamA);
    }

    // =====================================================================
    // 404 越境・存在しない ID
    // =====================================================================

    @Test
    @DisplayName("存在しないチームの slug は 404")
    void 存在しないチームは404() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{slug}/org-applications", "af-t-no-such-team")
                        .with(user(String.valueOf(adminA))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("越境: 他チームの membershipId を取り下げようとすると、存在しない ID と同じ 404 TEAM_070 で、行は消えない")
    void 越境した加盟IDは存在しないIDと同じ404() throws Exception {
        long appA = insertMembershipRow(teamA.id(), org.id(), "PENDING", "TEAM_APPLY", null, LocalDateTime.now());
        OrgFx orgB = newOrg();
        long appB = insertMembershipRow(teamB.id(), orgB.id(), "PENDING", "TEAM_APPLY", null, LocalDateTime.now());
        em.flush();

        MvcResult missing = mockMvc.perform(withdraw(adminB, teamB, 987_654_321L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TEAM_070")).andReturn();
        // team B の操作者が、team A の行の ID を自分のチームのパスで取り下げようとする
        MvcResult crossed = mockMvc.perform(withdraw(adminB, teamB, appA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TEAM_070")).andReturn();
        // team A の操作者が、team B の行の ID を自分のチームのパスで取り下げようとする
        MvcResult crossedReverse = mockMvc.perform(withdraw(adminA, teamA, appB))
                .andExpect(status().isNotFound()).andReturn();

        String missingBody = missing.getResponse().getContentAsString();
        assertThat(crossed.getResponse().getContentAsString())
                .as("越境した ID の 404 は、存在しない ID の 404 と本文まで一致する").isEqualTo(missingBody);
        assertThat(crossedReverse.getResponse().getContentAsString()).isEqualTo(missingBody);
        assertThat(countMemberships(teamA.id(), org.id())).as("他チームの操作では行が消えない").isEqualTo(1);
        assertThat(countMemberships(teamB.id(), orgB.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("一覧は自チームの申請だけを返す（他チームの申請は混ざらない）")
    void 一覧は自チームの申請だけ() throws Exception {
        OrgFx orgB = newOrg();
        insertMembershipRow(teamA.id(), org.id(), "PENDING", "TEAM_APPLY", null, LocalDateTime.now());
        insertMembershipRow(teamB.id(), orgB.id(), "PENDING", "TEAM_APPLY", null, LocalDateTime.now());
        em.flush();

        MvcResult result = mockMvc.perform(get("/api/v1/teams/{slug}/org-applications", teamA.slug())
                        .with(user(String.valueOf(adminA))))
                .andExpect(status().isOk()).andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).get("team").get("slug").asText()).isEqualTo(teamA.slug());
        assertThat(data.get(0).get("organization").get("slug").asText()).isEqualTo(org.slug());
    }

    // =====================================================================
    // 正当なら成功
    // =====================================================================

    @Test
    @DisplayName("正当: チーム ADMIN は申請(201)・一覧(200)・取下げ(204)ができる")
    void 正当なら成功する() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/teams/{slug}/org-applications", teamA.slug())
                        .with(user(String.valueOf(adminA)))
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody(org.slug())))
                .andExpect(status().isCreated()).andReturn();
        long membershipId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        mockMvc.perform(get("/api/v1/teams/{slug}/org-applications", teamA.slug())
                        .with(user(String.valueOf(adminA))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(withdraw(adminA, teamA, membershipId)).andExpect(status().isNoContent());
        assertThat(countMemberships(teamA.id(), org.id())).isZero();
    }

    // =====================================================================
    // ヘルパー
    // =====================================================================

    private void assertForbiddenForAllEndpoints(long actor, TeamFx team) throws Exception {
        ResultActions apply = mockMvc.perform(post("/api/v1/teams/{slug}/org-applications", team.slug())
                .with(user(String.valueOf(actor)))
                .contentType(MediaType.APPLICATION_JSON).content(applyBody(org.slug())));
        apply.andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("COMMON_002"));
        mockMvc.perform(get("/api/v1/teams/{slug}/org-applications", team.slug())
                        .with(user(String.valueOf(actor))))
                .andExpect(status().isForbidden());
        mockMvc.perform(withdraw(actor, team, 1L)).andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder withdraw(long actor, TeamFx team, long membershipId) {
        return delete("/api/v1/teams/{slug}/org-applications/{id}", team.slug(), membershipId)
                .with(user(String.valueOf(actor)));
    }

    private String applyBody(String organizationSlug) throws Exception {
        return objectMapper.writeValueAsString(Map.of("organizationSlug", organizationSlug));
    }
}
