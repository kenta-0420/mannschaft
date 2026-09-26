package com.mannschaft.app.admin;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 目安箱（{@code POST /api/v1/feedbacks}）の宛先契約テスト（CMP-260917-1135 陣2b）。
 *
 * <p>宛先は GENERAL（運営宛て）/ TEAM / ORGANIZATION の3つ（設計書 F10.1 §feedback_submissions）。</p>
 * <ul>
 *   <li>GENERAL: 誰でも投稿できる。{@code scopeId} を伴えば 400（ADMIN_FB_011）</li>
 *   <li>TEAM / ORGANIZATION: {@code scopeId} が null・0 なら 400（ADMIN_FB_011）。
 *       在籍メンバー（{@code AccessControlService#isMember}＝memberships 判定）だけが投稿でき、
 *       非メンバーと存在しない scopeId は同一応答（403 COMMON_002）</li>
 *   <li>それ以外の種別は 400（ADMIN_FB_011）</li>
 * </ul>
 *
 * <p>保存値は DB を直接 SELECT して検証する（応答 DTO だけでは保存先を証明できないため）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("目安箱の宛先契約テスト（CMP-260917-1135）")
class FeedbackDestinationContractIT extends AbstractMySqlIntegrationTest {

    private static final Long MEMBER_USER_ID = 9_311_001L;
    private static final Long OUTSIDER_USER_ID = 9_311_002L;
    private static final Long ROLE_ONLY_ADMIN_USER_ID = 9_311_003L;
    private static final Long NONEXISTENT_SCOPE_ID = 987_654_321L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long organizationId;

    @BeforeEach
    void setUp() {
        MembershipTestHelper.insertActiveUser(em, MEMBER_USER_ID);
        MembershipTestHelper.insertActiveUser(em, OUTSIDER_USER_ID);
        MembershipTestHelper.insertActiveUser(em, ROLE_ONLY_ADMIN_USER_ID);
        teamId = insertTeam("目安箱契約チーム");
        organizationId = insertOrganization("目安箱契約組織");
        MembershipTestHelper.insertMembership(em, MEMBER_USER_ID, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(
                em, MEMBER_USER_ID, ScopeType.ORGANIZATION, organizationId, RoleKind.MEMBER);
        // memberships を持たず user_roles の ADMIN だけを持つ利用者
        MembershipTestHelper.insertUserRole(em, ROLE_ONLY_ADMIN_USER_ID, "ADMIN", teamId, null);
        em.flush();
        em.clear();
    }

    @Nested
    @DisplayName("GENERAL（運営宛て）")
    class General {

        @Test
        @DisplayName("どのスコープにも所属しない利用者でも 201 で保存される（scope_id は NULL）")
        void 所属なしでも201() throws Exception {
            setAuthentication(OUTSIDER_USER_ID);
            MvcResult result = perform(body("GENERAL", null))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.scopeType").value("GENERAL"))
                    .andReturn();
            long id = readId(result);
            assertThat(selectScope(id)).containsExactly("GENERAL", null);
        }

        @Test
        @DisplayName("scopeId を伴うと 400（ADMIN_FB_011）で保存されない")
        void scopeId非nullは400() throws Exception {
            setAuthentication(MEMBER_USER_ID);
            perform(body("GENERAL", teamId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ADMIN_FB_011"));
            assertThat(countFeedbacks()).isZero();
        }
    }

    @Nested
    @DisplayName("TEAM / ORGANIZATION 宛て")
    class Scoped {

        @Test
        @DisplayName("TEAM で scopeId が null なら 400（ADMIN_FB_011）")
        void team_scopeId_null_400() throws Exception {
            setAuthentication(MEMBER_USER_ID);
            perform(body("TEAM", null))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ADMIN_FB_011"));
            assertThat(countFeedbacks()).isZero();
        }

        @Test
        @DisplayName("TEAM で scopeId が 0 なら 400（ADMIN_FB_011）")
        void team_scopeId_zero_400() throws Exception {
            setAuthentication(MEMBER_USER_ID);
            perform(body("TEAM", 0L))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ADMIN_FB_011"));
            assertThat(countFeedbacks()).isZero();
        }

        @Test
        @DisplayName("ORGANIZATION で scopeId が null なら 400（ADMIN_FB_011）")
        void organization_scopeId_null_400() throws Exception {
            setAuthentication(MEMBER_USER_ID);
            perform(body("ORGANIZATION", null))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ADMIN_FB_011"));
            assertThat(countFeedbacks()).isZero();
        }

        @Test
        @DisplayName("TEAM の在籍メンバーは 201 で、そのチーム宛てに保存される")
        void teamメンバーは201() throws Exception {
            setAuthentication(MEMBER_USER_ID);
            MvcResult result = perform(body("TEAM", teamId))
                    .andExpect(status().isCreated())
                    .andReturn();
            assertThat(selectScope(readId(result))).containsExactly("TEAM", teamId);
        }

        @Test
        @DisplayName("ORGANIZATION の在籍メンバーは 201 で、その組織宛てに保存される")
        void organizationメンバーは201() throws Exception {
            setAuthentication(MEMBER_USER_ID);
            MvcResult result = perform(body("ORGANIZATION", organizationId))
                    .andExpect(status().isCreated())
                    .andReturn();
            assertThat(selectScope(readId(result))).containsExactly("ORGANIZATION", organizationId);
        }

        @Test
        @DisplayName("TEAM の非メンバーは 403（COMMON_002）で保存されない")
        void team非メンバーは403() throws Exception {
            setAuthentication(OUTSIDER_USER_ID);
            perform(body("TEAM", teamId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
            assertThat(countFeedbacks()).isZero();
        }

        @Test
        @DisplayName("ORGANIZATION の非メンバーは 403（COMMON_002）で保存されない")
        void organization非メンバーは403() throws Exception {
            setAuthentication(OUTSIDER_USER_ID);
            perform(body("ORGANIZATION", organizationId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
            assertThat(countFeedbacks()).isZero();
        }

        @Test
        @DisplayName("存在しない scopeId は非メンバーと同一応答（ステータス・コード・メッセージ）")
        void 存在しないscopeIdは非メンバーと同一応答() throws Exception {
            setAuthentication(OUTSIDER_USER_ID);
            MvcResult outsider = perform(body("TEAM", teamId)).andReturn();
            MvcResult nonexistent = perform(body("TEAM", NONEXISTENT_SCOPE_ID)).andReturn();

            assertThat(outsider.getResponse().getStatus()).isEqualTo(403);
            assertThat(nonexistent.getResponse().getStatus()).isEqualTo(outsider.getResponse().getStatus());
            JsonNode a = errorNode(outsider);
            JsonNode b = errorNode(nonexistent);
            assertThat(b.path("code").asText()).isEqualTo(a.path("code").asText());
            assertThat(b.path("message").asText()).isEqualTo(a.path("message").asText());
            assertThat(countFeedbacks()).isZero();
        }

        @Test
        @DisplayName("同じ URL・同じ本文で認証主体だけ差し替えると結果が変わる（メンバー201・非メンバー403）")
        void 認証主体だけ差し替えると結果が変わる() throws Exception {
            Map<String, Object> sameBody = body("TEAM", teamId);

            setAuthentication(OUTSIDER_USER_ID);
            perform(sameBody).andExpect(status().isForbidden());

            setAuthentication(MEMBER_USER_ID);
            perform(sameBody).andExpect(status().isCreated());

            assertThat(countFeedbacks()).isEqualTo(1L);
        }

        @Test
        @DisplayName("user_roles の ADMIN だけで memberships を持たない利用者は、既存の在籍判定どおり 403")
        void userRolesのみの管理者は既存判定どおり403() throws Exception {
            setAuthentication(ROLE_ONLY_ADMIN_USER_ID);
            perform(body("TEAM", teamId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
            assertThat(countFeedbacks()).isZero();
        }
    }

    @Nested
    @DisplayName("宛先種別の検証")
    class ScopeTypeValidation {

        @Test
        @DisplayName("定義外の種別（PERSONAL）は 400（ADMIN_FB_011）")
        void 定義外の種別は400() throws Exception {
            setAuthentication(MEMBER_USER_ID);
            perform(body("PERSONAL", MEMBER_USER_ID))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ADMIN_FB_011"));
            assertThat(countFeedbacks()).isZero();
        }

        @Test
        @DisplayName("設計書の旧表記 PLATFORM も定義外として 400（ADMIN_FB_011）")
        void PLATFORMは400() throws Exception {
            setAuthentication(MEMBER_USER_ID);
            perform(body("PLATFORM", null))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ADMIN_FB_011"));
            assertThat(countFeedbacks()).isZero();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.ResultActions perform(Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(post("/api/v1/feedbacks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> body(String scopeType, Long scopeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", scopeType);
        body.put("scopeId", scopeId);
        body.put("category", "IDEA");
        body.put("title", "目安箱契約テスト");
        body.put("body", "本文");
        body.put("isAnonymous", false);
        return body;
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private long readId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private JsonNode errorNode(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("error");
    }

    private List<Object> selectScope(long id) {
        em.flush();
        Object[] row = (Object[]) em.createNativeQuery(
                        "SELECT scope_type, scope_id FROM feedback_submissions WHERE id = :id")
                .setParameter("id", id)
                .getSingleResult();
        Long scopeId = row[1] == null ? null : ((Number) row[1]).longValue();
        java.util.ArrayList<Object> values = new java.util.ArrayList<>();
        values.add(row[0]);
        values.add(scopeId);
        return values;
    }

    private long countFeedbacks() {
        em.flush();
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM feedback_submissions WHERE title = '目安箱契約テスト'")
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('fbdest-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('fbdest-o-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
