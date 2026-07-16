package com.mannschaft.app.cms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave3-B7: cms（ブログ）ドメインの {@code BlogSeriesController}（連載シリーズ）・
 * {@code BlogTagController}（タグ）CRUD の API 契約テスト（試練）。
 *
 * <p>正本: 依頼文（Wave3-B7 cms節）・{@code AccessControlService}
 * （{@code checkMembership}=作成/{@code checkAdminOrAbove}=更新・削除）・
 * {@code BlogSeriesService#checkScopeAdmin} / {@code BlogTagService#checkScopeAdmin}
 * （entity 由来 scope の ADMIN 限定を新設）。</p>
 *
 * <p>対象（従来 authz ゼロだった書込EP。create すら未是正だった）:</p>
 * <ul>
 *   <li>{@code BlogSeriesController}/{@code BlogSeriesService}: createSeries（checkMembership）・
 *       updateSeries/deleteSeries（entity由来scopeのcheckAdminOrAbove）</li>
 *   <li>{@code BlogTagController}/{@code BlogTagService}: createTag（checkMembership）・
 *       updateTag/deleteTag（entity由来scopeのcheckAdminOrAbove）</li>
 * </ul>
 *
 * <p>金型: {@code IncidentScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL。
 * create は body 由来 scopeId、update/delete は entity 由来 scope で認可判定・ID-only エンドポイントの
 * 越境は 403 に畳み込む）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("cms ドメイン シリーズ/タグ CRUD API 契約テスト（認可根治 Wave3-B7）")
class CmsSeriesTagScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;
    private Long adminBId;
    private Long memberAId;
    private Long outsiderId;

    @BeforeEach
    void setUp() {
        insertRoleIfAbsent("ADMIN", "管理者", 2);

        teamAId = insertTeam("CMS-STG認可契約チームA");
        teamBId = insertTeam("CMS-STG認可契約チームB");

        adminAId = insertUser("cms-stg-authz-admin-a@example.com");
        adminBId = insertUser("cms-stg-authz-admin-b@example.com");
        memberAId = insertUser("cms-stg-authz-member-a@example.com");
        outsiderId = insertUser("cms-stg-authz-outsider@example.com");

        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // シリーズ作成(createSeries) — checkMembership のみ（ADMIN不要）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("シリーズ作成(createSeries)")
    class CreateSeries {

        @Test
        @DisplayName("非メンバーの作成は403（checkMembership）")
        void 非メンバーの作成は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/blog/series")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createSeriesBody(teamAId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)の作成は201（createはcheckMembershipのみ・ADMIN限定ではない）")
        void 一般メンバーの作成は201() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/blog/series")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createSeriesBody(teamAId))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // シリーズ更新(updateSeries)・削除(deleteSeries) — entity由来scopeのcheckAdminOrAbove
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("シリーズ更新(updateSeries)・削除(deleteSeries)")
    class UpdateAndDeleteSeries {

        @Test
        @DisplayName("非ADMINメンバーの更新は403")
        void 非ADMINメンバーの更新は403() throws Exception {
            Long seriesId = createSeriesAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(put("/api/v1/blog/series/{id}", seriesId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateSeriesBody("改題"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの更新は403（entity由来scopeで認可判定）")
        void 他チームADMINの更新は403() throws Exception {
            Long seriesId = createSeriesAsAdminA();

            setAuthentication(adminBId);
            mockMvc.perform(put("/api/v1/blog/series/{id}", seriesId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateSeriesBody("乗っ取り"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの更新は200")
        void 正当ADMINの更新は200() throws Exception {
            Long seriesId = createSeriesAsAdminA();

            setAuthentication(adminAId);
            mockMvc.perform(put("/api/v1/blog/series/{id}", seriesId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateSeriesBody("改題済"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("改題済"));
        }

        @Test
        @DisplayName("不在シリーズの更新は404（CMS_003・IDOR秘匿）")
        void 不在シリーズの更新は404() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(put("/api/v1/blog/series/{id}", 999_999_999L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateSeriesBody("改題"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CMS_003"));
        }

        @Test
        @DisplayName("非ADMINメンバーの削除は403")
        void 非ADMINメンバーの削除は403() throws Exception {
            Long seriesId = createSeriesAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(delete("/api/v1/blog/series/{id}", seriesId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの削除は204")
        void 正当ADMINの削除は204() throws Exception {
            Long seriesId = createSeriesAsAdminA();

            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/blog/series/{id}", seriesId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // タグ作成(createTag) — checkMembership のみ（ADMIN不要）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("タグ作成(createTag)")
    class CreateTag {

        @Test
        @DisplayName("非メンバーの作成は403（checkMembership）")
        void 非メンバーの作成は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/blog/tags")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTagBody(teamAId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)の作成は201（createはcheckMembershipのみ・ADMIN限定ではない）")
        void 一般メンバーの作成は201() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/blog/tags")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTagBody(teamAId))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // タグ更新(updateTag)・削除(deleteTag) — entity由来scopeのcheckAdminOrAbove
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("タグ更新(updateTag)・削除(deleteTag)")
    class UpdateAndDeleteTag {

        @Test
        @DisplayName("非ADMINメンバーの更新は403")
        void 非ADMINメンバーの更新は403() throws Exception {
            Long tagId = createTagAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(put("/api/v1/blog/tags/{id}", tagId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTagBody("改名"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの更新は403（entity由来scopeで認可判定）")
        void 他チームADMINの更新は403() throws Exception {
            Long tagId = createTagAsAdminA();

            setAuthentication(adminBId);
            mockMvc.perform(put("/api/v1/blog/tags/{id}", tagId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTagBody("乗っ取り"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの更新は200")
        void 正当ADMINの更新は200() throws Exception {
            Long tagId = createTagAsAdminA();

            setAuthentication(adminAId);
            mockMvc.perform(put("/api/v1/blog/tags/{id}", tagId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTagBody("改名済"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("改名済"));
        }

        @Test
        @DisplayName("不在タグの更新は404（CMS_002・IDOR秘匿）")
        void 不在タグの更新は404() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(put("/api/v1/blog/tags/{id}", 999_999_999L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTagBody("改名"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CMS_002"));
        }

        @Test
        @DisplayName("非ADMINメンバーの削除は403")
        void 非ADMINメンバーの削除は403() throws Exception {
            Long tagId = createTagAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(delete("/api/v1/blog/tags/{id}", tagId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの削除は204")
        void 正当ADMINの削除は204() throws Exception {
            Long tagId = createTagAsAdminA();

            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/blog/tags/{id}", tagId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** adminA の認証コンテキストでチームAのシリーズを1件作成し、そのIDを返す。 */
    private Long createSeriesAsAdminA() throws Exception {
        setAuthentication(adminAId);
        String resp = mockMvc.perform(post("/api/v1/blog/series")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createSeriesBody(teamAId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /** adminA の認証コンテキストでチームAのタグを1件作成し、そのIDを返す。 */
    private Long createTagAsAdminA() throws Exception {
        setAuthentication(adminAId);
        String resp = mockMvc.perform(post("/api/v1/blog/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createTagBody(teamAId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    private Map<String, Object> createSeriesBody(Long teamId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("teamId", teamId);
        body.put("name", "認可契約テストシリーズ " + System.nanoTime());
        return body;
    }

    private Map<String, Object> updateSeriesBody(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        return body;
    }

    private Map<String, Object> createTagBody(Long teamId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("teamId", teamId);
        body.put("name", "認可契約テストタグ" + System.nanoTime());
        return body;
    }

    private Map<String, Object> updateTagBody(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        return body;
    }

    /** roles を name で引く idempotent seed（グローバル参照テーブルのため deleteAll しない）。 */
    private void insertRoleIfAbsent(String name, String displayName, int priority) {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (count.longValue() > 0) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :priority, 0, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", displayName)
                .setParameter("priority", priority)
                .executeUpdate();
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'CMS-STG契約', 'テスト', 'CMS-STG契約テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('cms-stg-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
