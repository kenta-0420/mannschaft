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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * F01.2.1 8-D — メンバー一覧（{@code /members}・{@code /members/all}）の {@code permissionGroups} を載せる条件の認可契約テスト。
 *
 * <p>設計書（F01.2 02_api_design.md）の条件は「チームメンバーの ADMIN／DEPUTY_ADMIN」。実 Security・実 DB・実 AccessControlService
 * （モックしない）で、対象チームの ADMIN/DEPUTY_ADMIN 割当がある者にだけ載ることを確かめる。
 * 所属の無い SYSTEM_ADMIN 単独には載せない（権限グループ一覧 API が checkAdminOrAbove で SYSTEM_ADMIN 単独を拒否するのと同じ判定）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 8-D メンバー一覧 permissionGroups の認可契約（実判定）")
class TeamMembersPermissionGroupsAuthzIT extends TeamAffiliationItSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private TeamFx team;
    private TeamFx otherTeam;
    private long ta;
    private long td;
    private long tm;
    private long tg;
    private long sysOnly;
    private long sysAndTa;
    private long otherAdmin;

    @BeforeEach
    void setUp() {
        seedAffiliationPermission();
        team = newTeam();
        otherTeam = newTeam();
        ta = newUser();
        td = newUser();
        tm = newUser();
        tg = newUser();
        sysOnly = newUser();
        sysAndTa = newUser();
        otherAdmin = newUser();
        makeTeamAdmin(ta, team.id());
        makeTeamDeputy(td, team.id());
        makeTeamMember(tm, team.id());
        makeTeamMember(tg, team.id());
        grantAffiliationByPermissionGroup(tg, team.id(), "MEMBER");
        makeTeamAdmin(otherAdmin, otherTeam.id());
        MembershipTestHelper.insertUserRole(em, sysOnly, "SYSTEM_ADMIN", null, null);
        makeTeamAdmin(sysAndTa, team.id());
        MembershipTestHelper.insertUserRole(em, sysAndTa, "SYSTEM_ADMIN", null, null);
        em.flush();
        em.clear();
    }

    private JsonNode members(long actor, String path) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/teams/{slug}" + path, team.slug())
                        .with(user(String.valueOf(actor))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("actor=%d path=%s", actor, path).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private JsonNode row(JsonNode data, long userId) {
        for (JsonNode node : data) {
            if (node.get("userId").asLong() == userId) {
                return node;
            }
        }
        throw new AssertionError("userId=" + userId + " not in members");
    }

    private void assertAttached(long actor) throws Exception {
        for (String path : new String[] {"/members", "/members/all"}) {
            JsonNode data = members(actor, path);
            assertThat(row(data, tg).get("permissionGroups")).as("actor=%d %s tg", actor, path).hasSize(1);
            assertThat(row(data, tm).get("permissionGroups")).as("actor=%d %s tm", actor, path).isEmpty();
            // ADMIN ロールは対象外（空配列）
            assertThat(row(data, ta).get("permissionGroups")).as("actor=%d %s ta", actor, path).isEmpty();
        }
    }

    private void assertNotAttached(long actor) throws Exception {
        for (String path : new String[] {"/members", "/members/all"}) {
            JsonNode data = members(actor, path);
            for (JsonNode node : data) {
                assertThat(node.has("permissionGroups")).as("actor=%d %s user=%s", actor, path, node.get("userId")).isFalse();
            }
        }
    }

    @Test
    @DisplayName("対象チームの ADMIN には載る（MEMBER に割り当てた権限グループの ID・名前が取れる）")
    void チームADMINには載る() throws Exception {
        assertAttached(ta);
    }

    @Test
    @DisplayName("対象チームの DEPUTY_ADMIN には載る")
    void チームDEPUTYには載る() throws Exception {
        assertAttached(td);
    }

    @Test
    @DisplayName("SYSTEM_ADMIN とチーム ADMIN の兼任者には載る")
    void 兼任者には載る() throws Exception {
        assertAttached(sysAndTa);
    }

    @Test
    @DisplayName("対象チームに所属しない SYSTEM_ADMIN 単独には載せない（権限グループ一覧 API と同じ判定）")
    void SYSTEM_ADMIN単独には載せない() throws Exception {
        assertNotAttached(sysOnly);
    }

    @Test
    @DisplayName("一般メンバー（付与済みの MEMBER を含む）・他チームの ADMIN には載せない")
    void 一般メンバーと他チームADMINには載せない() throws Exception {
        assertNotAttached(tm);
        assertNotAttached(tg);
        assertNotAttached(otherAdmin);
    }
}
