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
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F01.2.1 8-D — 権限グループの作成・編集 UI が依存する BE 契約を、実判定（モックしない）で固定する。
 *
 * <ul>
 *   <li>権限カタログ（{@code GET /api/v1/permissions}）の集合 ＝ 権限グループ作成 API が受け付ける {@code permissionIds} の集合
 *       （TEAM/ORGANIZATION 分類を含み、PLATFORM 分類は含まない。組織・チームのどちらで問い合わせても同じ）</li>
 *   <li>{@code /me/permissions} の {@code canGrantAdminOnlyPermissions} ＝ ADMIN 専用権限（MANAGE_ORG_AFFILIATION 等）を含む
 *       権限グループの作成が実際に通るか。FE はこの値で UI を出し分けるので、BE の {@code checkScopeAdminOnly} と一致しなければならない
 *       （SYSTEM_ADMIN とチーム ADMIN の兼任者は、ADMIN 割当があっても拒否される）</li>
 *   <li>権限カタログの認可はスコープの ADMIN+（SYSTEM_ADMIN 単独・DEPUTY 未満は 403 ではなく ADMIN+ の判定どおり）</li>
 * </ul>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 8-D 権限グループ作成 UI の BE 契約（カタログ集合・ADMIN専用権限の付与可否）")
class TeamPermissionGroupGrantContractIT extends TeamAffiliationItSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private TeamFx team;
    private OrgFx org;
    private long ta;
    private long td;
    private long tm;
    private long sysOnly;
    private long sysAndTa;
    private long orgAdmin;
    private String orgPerm;
    private String platformPerm;
    private String sfx;

    @BeforeEach
    void setUp() {
        seedAffiliationPermission();
        team = newTeam();
        org = newOrg();
        ta = newUser();
        td = newUser();
        tm = newUser();
        sysOnly = newUser();
        sysAndTa = newUser();
        orgAdmin = newUser();
        makeTeamAdmin(ta, team.id());
        makeTeamDeputy(td, team.id());
        makeTeamMember(tm, team.id());
        makeTeamAdmin(sysAndTa, team.id());
        makeOrgAdmin(orgAdmin, org.id());
        MembershipTestHelper.insertUserRole(em, sysOnly, "SYSTEM_ADMIN", null, null);
        MembershipTestHelper.insertUserRole(em, sysAndTa, "SYSTEM_ADMIN", null, null);
        sfx = Long.toString(System.nanoTime());
        orgPerm = "IT8D_ORG_" + sfx;
        platformPerm = "IT8D_PLATFORM_" + sfx;
        insertPermission(orgPerm, "ORGANIZATION");
        insertPermission(platformPerm, "PLATFORM");
        em.flush();
        em.clear();
    }

    private void insertPermission(String name, String scope) {
        em.createNativeQuery("INSERT INTO permissions (name, display_name, scope, created_at, updated_at) "
                        + "VALUES (:n, :n, :s, NOW(), NOW())")
                .setParameter("n", name).setParameter("s", scope).executeUpdate();
    }

    private long permissionId(String name) {
        return ((Number) em.createNativeQuery("SELECT id FROM permissions WHERE name = :n")
                .setParameter("n", name).getSingleResult()).longValue();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Set<String> catalogNames(long actor, String scopeType, long scopeId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/permissions")
                        .param("scopeType", scopeType).param("scopeId", String.valueOf(scopeId))
                        .with(user(String.valueOf(actor)))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Set<String> names = new TreeSet<>();
        for (JsonNode node : json(result).get("data")) {
            names.add(node.get("name").asText());
        }
        return names;
    }

    private int createGroup(long actor, String name, long permissionId) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", name, "targetRole", "MEMBER", "permissionIds", List.of(permissionId)));
        return mockMvc.perform(post("/api/v1/teams/{slug}/permission-groups", team.slug())
                        .with(user(String.valueOf(actor)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode myPermissions(long actor) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/teams/{slug}/me/permissions", team.slug())
                        .with(user(String.valueOf(actor)))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json(result).get("data");
    }

    // ---------------------------------------------------------------
    // カタログ集合 ＝ 作成 API が受け付ける集合
    // ---------------------------------------------------------------

    @Test
    @DisplayName("カタログは TEAM/ORGANIZATION 分類を含み PLATFORM 分類を含まず、組織・チームのどちらで問い合わせても同じ集合")
    void カタログの集合はスコープに依らず同じ() throws Exception {
        Set<String> forTeam = catalogNames(ta, "TEAM", team.id());
        Set<String> forOrg = catalogNames(orgAdmin, "ORGANIZATION", org.id());

        assertThat(forTeam).contains(MANAGE_ORG_AFFILIATION, orgPerm).doesNotContain(platformPerm);
        assertThat(forOrg).isEqualTo(forTeam);
    }

    @Test
    @DisplayName("カタログの全項目は作成 API で受け付けられ（201）、カタログに無い PLATFORM 分類は拒否される（グループは作られない）")
    void カタログと作成APIの受け付け集合が一致する() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/permissions")
                        .param("scopeType", "TEAM").param("scopeId", String.valueOf(team.id()))
                        .with(user(String.valueOf(ta)))).andReturn();
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : json(result).get("data")) {
            ids.add(node.get("id").asLong());
        }
        assertThat(ids).isNotEmpty();
        int i = 0;
        for (long id : ids) {
            assertThat(createGroup(ta, "cat-" + sfx + "-" + i++, id)).as("permissionId=%d", id).isEqualTo(201);
        }

        String rejected = "plat-" + sfx;
        assertThat(createGroup(ta, rejected, permissionId(platformPerm))).isBetween(400, 499);
        em.flush();
        em.clear();
        assertThat(((Number) em.createNativeQuery("SELECT COUNT(*) FROM permission_groups WHERE name = :n")
                .setParameter("n", rejected).getSingleResult()).longValue()).isZero();
    }

    @Test
    @DisplayName("カタログの認可はスコープの ADMIN+: ADMIN・DEPUTY は 200、MEMBER・SYSTEM_ADMIN 単独・他スコープの ADMIN は 403")
    void カタログの認可はスコープADMIN以上() throws Exception {
        for (long allowed : new long[] {ta, td, sysAndTa}) {
            assertThat(catalogNames(allowed, "TEAM", team.id())).isNotEmpty();
        }
        for (long denied : new long[] {tm, sysOnly, orgAdmin}) {
            int status = mockMvc.perform(get("/api/v1/permissions")
                            .param("scopeType", "TEAM").param("scopeId", String.valueOf(team.id()))
                            .with(user(String.valueOf(denied)))).andReturn().getResponse().getStatus();
            assertThat(status).as("actor=%d", denied).isEqualTo(403);
        }
        int bad = mockMvc.perform(get("/api/v1/permissions")
                        .param("scopeType", "PLATFORM").param("scopeId", String.valueOf(team.id()))
                        .with(user(String.valueOf(ta)))).andReturn().getResponse().getStatus();
        assertThat(bad).isEqualTo(400);
    }

    // ---------------------------------------------------------------
    // FE が使う canGrantAdminOnlyPermissions ＝ BE の実判定
    // ---------------------------------------------------------------

    @Test
    @DisplayName("canGrantAdminOnlyPermissions は、ADMIN 専用権限を含むグループを作れるか（BE の checkScopeAdminOnly）と一致する")
    void canGrantAdminOnlyPermissionsはBEの判定と一致する() throws Exception {
        long affiliation = permissionId(MANAGE_ORG_AFFILIATION);
        long ordinary = permissionId(orgPerm);
        int n = 0;
        // actor, 期待 scopeAdmin, 期待 canGrant
        Object[][] cases = {
                {ta, true, true},        // チーム ADMIN
                {sysAndTa, true, false}, // SYSTEM_ADMIN 兼任: ADMIN 割当はあるが ADMIN 専用権限は付与・剥奪できない
                {td, false, false},      // DEPUTY_ADMIN
        };
        for (Object[] c : cases) {
            long actor = (long) c[0];
            JsonNode me = myPermissions(actor);
            assertThat(me.get("scopeAdmin").asBoolean()).as("scopeAdmin actor=%d", actor).isEqualTo((boolean) c[1]);
            boolean canGrant = me.get("canGrantAdminOnlyPermissions").asBoolean();
            assertThat(canGrant).as("canGrant actor=%d", actor).isEqualTo((boolean) c[2]);

            int adminOnlyStatus = createGroup(actor, "grant-" + sfx + "-" + n++, affiliation);
            assertThat(adminOnlyStatus >= 200 && adminOnlyStatus < 300)
                    .as("ADMIN 専用権限を含む作成が通るか actor=%d status=%d", actor, adminOnlyStatus).isEqualTo(canGrant);
            if (!canGrant) {
                assertThat(adminOnlyStatus).isEqualTo(403);
            }
        }
        // 兼任者でも ADMIN 専用でない権限のグループは作れる（UI 全体を閉じない）
        assertThat(createGroup(sysAndTa, "ordinary-" + sfx, ordinary)).isEqualTo(201);
    }
}
