package com.mannschaft.app.property;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave3-B5: property ドメイン（{@code PropertyWorkPackageController} /
 * {@code VendorController}）API 契約テスト（試練）。
 *
 * <p>正本: 依頼文（Wave3-B5 property節）・{@code AccessControlService}
 * （{@code checkMembership}/{@code checkAdminOrAbove}）・
 * {@code PropertyWorkPackageService#getByIdInScope}（BOLA対策で新設）。
 * 金型: {@code DigestScopeContractIT} / {@code ParkingScopeContractIT}。</p>
 *
 * <p>対象:</p>
 * <ul>
 *   <li>PropertyWorkPackageController: 一覧(閲覧)・詳細(閲覧+BOLA)・作成/更新/ステータス変更/削除(管理)</li>
 *   <li>VendorController: 一覧/詳細(閲覧)・作成/更新/削除(管理)。BOLA は既存
 *       {@code VendorService.ensureScopeMatches}（PROPERTY_005）が担当（本 IT で再確認）</li>
 * </ul>
 *
 * <p>package/vendor の seed は実 Controller 経由（ADMIN で作成）で行い、
 * 作成系エンドポイント自体の認可検証も兼ねる。</p>
 *
 * <p>ADMIN 役の被験者は {@code checkMembership}（memberships 表）と
 * {@code checkAdminOrAbove}（user_roles 表）の両方を満たすよう二重に seed する
 * （認可根治戦役 Wave0+1 で確立した既知の地雷）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("property ドメイン API 契約テスト（認可根治 Wave3-B5）")
class PropertyScopeContractIT extends AbstractMySqlIntegrationTest {

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

        teamAId = insertTeam("PR認可契約チームA");
        teamBId = insertTeam("PR認可契約チームB");

        adminAId = insertUser("pr-authz-admin-a@example.com");
        adminBId = insertUser("pr-authz-admin-b@example.com");
        memberAId = insertUser("pr-authz-member-a@example.com");
        outsiderId = insertUser("pr-authz-outsider@example.com");

        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // PropertyWorkPackageController — 一覧(閲覧)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("パッケージ一覧(list)")
    class ListPackages {

        @Test
        @DisplayName("非メンバーの一覧取得は403")
        void 非メンバーの一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{scopeId}/property-history", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの一覧取得は403（越境拒否）")
        void 他チームADMINの一覧は403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(get("/api/v1/teams/{scopeId}/property-history", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("一般メンバーの一覧取得は200")
        void 一般メンバーの一覧は200() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/teams/{scopeId}/property-history", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // PropertyWorkPackageController — 詳細(閲覧+BOLA)・作成(管理)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("パッケージ作成(create)・詳細取得(get)")
    class CreateAndGet {

        @Test
        @DisplayName("非ADMINメンバーの作成は403（変更系はcheckAdminOrAbove）")
        void 非ADMINメンバーの作成は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/teams/{scopeId}/property-history", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(packageBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの作成は201")
        void 正当ADMINの作成は201() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/teams/{scopeId}/property-history", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(packageBody())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists());
        }

        @Test
        @DisplayName("非メンバーの詳細取得は403")
        void 非メンバーの詳細は403() throws Exception {
            Long packageId = createPackageAsAdminA();

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{scopeId}/property-history/{id}", teamAId, packageId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINがチームAのパッケージIDを他チームscopeで直指定すると404（BOLA: scope不一致存在秘匿）")
        void 他チームADMINの越境詳細は404() throws Exception {
            Long packageId = createPackageAsAdminA();

            setAuthentication(adminBId);
            // teamB の scope で teamA のパッケージIDを直指定
            mockMvc.perform(get("/api/v1/teams/{scopeId}/property-history/{id}", teamBId, packageId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("PROPERTY_001"));
        }

        @Test
        @DisplayName("正当メンバーの詳細取得は200")
        void 正当メンバーの詳細は200() throws Exception {
            Long packageId = createPackageAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/teams/{scopeId}/property-history/{id}", teamAId, packageId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(packageId));
        }

        @Test
        @DisplayName("不在IDの詳細取得は404（PROPERTY_001の404マッピング・存在秘匿）")
        void 不在IDの詳細は404() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(get("/api/v1/teams/{scopeId}/property-history/{id}", teamAId, 999_999_999L))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("PROPERTY_001"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // PropertyWorkPackageController — 更新・ステータス変更・削除(管理+BOLA)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("パッケージ更新(update)・ステータス変更(changeStatus)・削除(delete)")
    class Mutations {

        @Test
        @DisplayName("非ADMINメンバーの更新は403")
        void 非ADMINメンバーの更新は403() throws Exception {
            Long packageId = createPackageAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(put("/api/v1/teams/{scopeId}/property-history/{id}", teamAId, packageId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(packageBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINがチームAのパッケージを他チームscopeで更新しようとすると404（BOLA）")
        void 他チームADMINの越境更新は404() throws Exception {
            Long packageId = createPackageAsAdminA();

            setAuthentication(adminBId);
            mockMvc.perform(put("/api/v1/teams/{scopeId}/property-history/{id}", teamBId, packageId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(packageBody())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("PROPERTY_001"));
        }

        @Test
        @DisplayName("正当ADMINの更新は200")
        void 正当ADMINの更新は200() throws Exception {
            Long packageId = createPackageAsAdminA();

            setAuthentication(adminAId);
            mockMvc.perform(put("/api/v1/teams/{scopeId}/property-history/{id}", teamAId, packageId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(packageBody())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(packageId));
        }

        @Test
        @DisplayName("非ADMINメンバーのステータス変更は403")
        void 非ADMINメンバーのステータス変更は403() throws Exception {
            Long packageId = createPackageAsAdminA();

            setAuthentication(memberAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "IN_PROGRESS");
            mockMvc.perform(patch("/api/v1/teams/{scopeId}/property-history/{id}/status", teamAId, packageId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("他チームADMINがチームAのパッケージを他チームscopeでステータス変更しようとすると404（BOLA）")
        void 他チームADMINの越境ステータス変更は404() throws Exception {
            Long packageId = createPackageAsAdminA();

            setAuthentication(adminBId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "IN_PROGRESS");
            mockMvc.perform(patch("/api/v1/teams/{scopeId}/property-history/{id}/status", teamBId, packageId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("PROPERTY_001"));
        }

        @Test
        @DisplayName("正当ADMINのステータス変更は200")
        void 正当ADMINのステータス変更は200() throws Exception {
            Long packageId = createPackageAsAdminA();

            setAuthentication(adminAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "IN_PROGRESS");
            mockMvc.perform(patch("/api/v1/teams/{scopeId}/property-history/{id}/status", teamAId, packageId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        }

        @Test
        @DisplayName("非ADMINメンバーの削除は403")
        void 非ADMINメンバーの削除は403() throws Exception {
            Long packageId = createPackageAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(delete("/api/v1/teams/{scopeId}/property-history/{id}", teamAId, packageId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("他チームADMINがチームAのパッケージを他チームscopeで削除しようとすると404（BOLA）")
        void 他チームADMINの越境削除は404() throws Exception {
            Long packageId = createPackageAsAdminA();

            setAuthentication(adminBId);
            mockMvc.perform(delete("/api/v1/teams/{scopeId}/property-history/{id}", teamBId, packageId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("PROPERTY_001"));
        }

        @Test
        @DisplayName("正当ADMINの削除は204")
        void 正当ADMINの削除は204() throws Exception {
            Long packageId = createPackageAsAdminA();

            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{scopeId}/property-history/{id}", teamAId, packageId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // VendorController — 一覧/詳細(閲覧)・作成/更新/削除(管理)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("業者一覧(list)・詳細(get)")
    class VendorListAndGet {

        @Test
        @DisplayName("非メンバーの業者一覧取得は403")
        void 非メンバーの一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{scopeId}/vendors", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバーの業者一覧取得は200")
        void 一般メンバーの一覧は200() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/teams/{scopeId}/vendors", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバーの業者詳細取得は403")
        void 非メンバーの詳細は403() throws Exception {
            Long vendorId = createVendorAsAdminA();

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{scopeId}/vendors/{id}", teamAId, vendorId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINが業者IDを他チームscopeで直指定すると404（BOLA: 既存ensureScopeMatches）")
        void 他チームADMINの越境詳細は404() throws Exception {
            Long vendorId = createVendorAsAdminA();

            setAuthentication(adminBId);
            mockMvc.perform(get("/api/v1/teams/{scopeId}/vendors/{id}", teamBId, vendorId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("PROPERTY_005"));
        }
    }

    @Nested
    @DisplayName("業者作成(create)・更新(update)・削除(delete)")
    class VendorMutations {

        @Test
        @DisplayName("非ADMINメンバーの業者作成は403")
        void 非ADMINメンバーの作成は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/teams/{scopeId}/vendors", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(vendorBody("業者A"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの業者作成は201")
        void 正当ADMINの作成は201() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/teams/{scopeId}/vendors", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(vendorBody("業者B"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists());
        }

        @Test
        @DisplayName("非ADMINメンバーの業者更新は403")
        void 非ADMINメンバーの更新は403() throws Exception {
            Long vendorId = createVendorAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(put("/api/v1/teams/{scopeId}/vendors/{id}", teamAId, vendorId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(vendorBody("業者改名"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの業者更新は200")
        void 正当ADMINの更新は200() throws Exception {
            Long vendorId = createVendorAsAdminA();

            setAuthentication(adminAId);
            mockMvc.perform(put("/api/v1/teams/{scopeId}/vendors/{id}", teamAId, vendorId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(vendorBody("業者改名済"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("業者改名済"));
        }

        @Test
        @DisplayName("非ADMINメンバーの業者削除は403")
        void 非ADMINメンバーの削除は403() throws Exception {
            Long vendorId = createVendorAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(delete("/api/v1/teams/{scopeId}/vendors/{id}", teamAId, vendorId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの業者削除は204")
        void 正当ADMINの削除は204() throws Exception {
            Long vendorId = createVendorAsAdminA();

            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{scopeId}/vendors/{id}", teamAId, vendorId))
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

    /** ADMIN-A の認証コンテキストで正規のパッケージを 1 件作成し、その ID を返す。 */
    private Long createPackageAsAdminA() throws Exception {
        setAuthentication(adminAId);
        String body = mockMvc.perform(post("/api/v1/teams/{scopeId}/property-history", teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(packageBody())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    /** ADMIN-A の認証コンテキストで正規の業者を 1 件作成し、その ID を返す。 */
    private Long createVendorAsAdminA() throws Exception {
        setAuthentication(adminAId);
        String body = mockMvc.perform(post("/api/v1/teams/{scopeId}/vendors", teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(vendorBody("契約テスト業者 " + System.nanoTime()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    /** パッケージ作成/更新リクエスト body（必須項目のみ。省略可フィールドは明示 null を避けて省略）。 */
    private Map<String, Object> packageBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workType", "RENOVATION");
        body.put("title", "認可契約テスト工事 " + System.nanoTime());
        body.put("isDisclosable", true);
        body.put("visibility", "MEMBERS_MASKED");
        body.put("version", 0);
        return body;
    }

    private Map<String, Object> vendorBody(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name + " " + System.nanoTime());
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
                                + "VALUES (:email, 'PR契約', 'テスト', 'PR契約テスト', 'ACTIVE', "
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
                                + "CONCAT('pr-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
