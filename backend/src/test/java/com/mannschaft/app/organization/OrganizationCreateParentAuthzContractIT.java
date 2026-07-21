package com.mannschaft.app.organization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治 Wave6 追加戦 — 組織作成における親組織指定（{@code parentOrganizationId}）の認可契約テスト。
 *
 * <h3>敷設した仕様</h3>
 * <ul>
 *   <li><b>親組織の指定なし（null）</b> → 従来どおり認証済みユーザーなら誰でも作成できる（201）。
 *       ここに認可を課すと全ユーザーが組織を作れなくなるため、正常系を最優先で固定する。</li>
 *   <li><b>親組織が実在しない</b> → {@code ORG_001} で 404（存在秘匿）。</li>
 *   <li><b>親組織は実在するが操作者が ADMIN/DEPUTY 相当でない</b> → {@code COMMON_002} で 403。</li>
 *   <li><b>親組織の ADMIN</b> → 201 で作成でき、{@code parent_organization_id} が実際に格納される。</li>
 * </ul>
 *
 * <h3>金型</h3>
 * <p>権限判定は独自実装せず、同クラスの兄弟 EP（{@code renameSlug} / {@code deleteOrganization} /
 * {@code archiveOrganization}）と同じ {@code AccessControlService.checkAdminOrAbove} に委譲する（F00 正準）。
 * 不在時の 404 も同ドメインの {@code ORG_001} 規約に揃えた。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("組織作成の親組織指定 認可契約テスト（Wave6 追加戦）")
class OrganizationCreateParentAuthzContractIT extends AbstractMySqlIntegrationTest {

    private static final String ENDPOINT = "/api/v1/organizations";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long parentOrgId;

    private Long parentAdminId;    // 親組織の ADMIN（正当）
    private Long parentMemberId;   // 親組織の非 ADMIN メンバー
    private Long outsiderId;       // どこにも所属しない非メンバー

    @BeforeEach
    void setUp() {
        parentOrgId = insertOrganization("W6PARENT 親組織");

        parentAdminId = insertUser("w6parent-admin@example.com");
        parentMemberId = insertUser("w6parent-member@example.com");
        outsiderId = insertUser("w6parent-outsider@example.com");

        // checkAdminOrAbove（user_roles）と membership（memberships）は別系統のため ADMIN には両方張る。
        MembershipTestHelper.insertMembership(em, parentAdminId, ScopeType.ORGANIZATION, parentOrgId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, parentAdminId, "ADMIN", null, parentOrgId);

        // 一般メンバーは memberships のみ（ADMIN 判定は通らない）。
        MembershipTestHelper.insertMembership(em, parentMemberId, ScopeType.ORGANIZATION, parentOrgId, RoleKind.MEMBER);

        // outsiderId はどこにも所属させない。

        // createOrganization は roles.name='ADMIN' を必須とする（test profile は Flyway seed 無効）。
        // insertUserRole 経由で ADMIN 行は投入済だが、MEMBER も membershipService.join の経路で要るため明示的に確保する。
        ensureRole("ADMIN");
        ensureRole("MEMBER");

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 正常系（最重要）— 親組織を指定しない従来どおりの組織作成
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. 親組織の指定なし（正常系・従来どおり）")
    class WithoutParent {

        @Test
        @DisplayName("非メンバー（どこにも所属しないユーザー）でも組織を作成できる（201）")
        void 親なしなら非メンバーでも作成できる() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post(ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("W6 親なし組織A", null)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("作成された組織の parent_organization_id は NULL のまま")
        void 親なし作成はparentがNULL() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post(ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("W6 親なし組織B", null)))
                    .andExpect(status().isCreated());

            assertThat(selectParentOrganizationId("W6 親なし組織B"))
                    .as("親組織を指定していないので NULL のまま格納される")
                    .isNull();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. 親組織を指定した場合の認可
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. 親組織を指定した場合の認可")
    class WithParent {

        @Test
        @DisplayName("親組織に無関係な非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post(ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("W6 子組織_非メンバー", parentOrgId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("親組織の一般メンバー（非ADMIN）も403")
        void 一般メンバーは403() throws Exception {
            setAuth(parentMemberId);
            mockMvc.perform(post(ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("W6 子組織_一般メンバー", parentOrgId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("親組織が実在しない場合は404（存在秘匿）")
        void 親組織不在は404() throws Exception {
            setAuth(parentAdminId);
            mockMvc.perform(post(ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("W6 子組織_親不在", 999_999_999L)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("403/404 で弾かれた場合、組織そのものが作成されていない")
        void 拒否時は組織が作成されない() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post(ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("W6 子組織_未作成確認", parentOrgId)))
                    .andExpect(status().isForbidden());

            assertThat(countOrganizationsByName("W6 子組織_未作成確認"))
                    .as("認可で弾かれた以上、組織レコードが生成されてはならない")
                    .isZero();
        }

        @Test
        @DisplayName("親組織のADMINは201で作成でき、parent_organization_id が格納される（正常系）")
        void 親組織ADMINは作成できる() throws Exception {
            setAuth(parentAdminId);
            mockMvc.perform(post(ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("W6 子組織_正当", parentOrgId)))
                    .andExpect(status().isCreated());

            assertThat(selectParentOrganizationId("W6 子組織_正当"))
                    .as("正当な ADMIN の指定した親組織はそのまま格納される")
                    .isEqualTo(parentOrgId);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private String body(String name, Long parentOrganizationId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("orgType", "OTHER");
        payload.put("visibility", "PRIVATE");
        payload.put("parentOrganizationId", parentOrganizationId);
        return objectMapper.writeValueAsString(payload);
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** test profile は Flyway seed が無効なため、必要な roles 行をオンデマンドで確保する。 */
    private void ensureRole(String roleName) {
        try {
            em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                    .setParameter("name", roleName)
                    .getSingleResult();
        } catch (NoResultException e) {
            em.createNativeQuery(
                            "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                    + "VALUES (:name, :name, 99, 0, NOW(), NOW())")
                    .setParameter("name", roleName)
                    .executeUpdate();
        }
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
                                + "VALUES (:email, 'W6PARENT', 'テスト', 'W6PARENT テスト', 'ACTIVE', "
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

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long selectParentOrganizationId(String name) {
        em.flush();
        em.clear();
        Object raw = em.createNativeQuery(
                        "SELECT parent_organization_id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        return raw == null ? null : ((Number) raw).longValue();
    }

    private long countOrganizationsByName(String name) {
        em.flush();
        em.clear();
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
