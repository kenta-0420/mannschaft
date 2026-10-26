package com.mannschaft.app.membership;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.entity.CheckinLocationEntity;
import com.mannschaft.app.membership.repository.CheckinLocationRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave3-B9: membership ドメイン（CheckinLocation CRUD/QR発行・CheckinStats統計）
 * API 契約テスト（試練）。
 *
 * <p>正本: 早馬（殿からの直接指示・Wave3-B9依頼文）。{@code AccessControlService}
 * （{@code checkMembership}/{@code checkAdminOrAbove}）。金型: {@code SupporterScopeContractIT}。</p>
 *
 * <p>認可モデル: 全EPが {@code /api/v1/teams/{teamId}/...} で scope（teamId）を path に
 * 明示的に宣言する「スコープ宣言型」EP のため、{@code checkMembership}/{@code checkAdminOrAbove}
 * で 403（COMMON_002）。拠点ID自体の scope 突合は既存の
 * {@code findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull}（WHERE 句で scope 一致を要求）が
 * 元々担保しており、越境 locationId は該当 WHERE がヒットせず 404（MEMBERSHIP_019）になる。
 * 本戦役で追加したのは、この既存 404 ガードの手前にあった「認可チェック皆無」の穴を塞ぐことである。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("membership CheckinLocation/CheckinStats 認可契約テスト（Wave3-B9）")
class CheckinLocationScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CheckinLocationRepository locationRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;
    private Long adminBId;
    private Long memberAId;
    private Long outsiderId;

    private CheckinLocationEntity locationA; // teamA の拠点

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("CL認可契約チームA");
        teamBId = insertTeam("CL認可契約チームB");

        adminAId = insertUser("cl-authz-admin-a@example.com");
        adminBId = insertUser("cl-authz-admin-b@example.com");
        memberAId = insertUser("cl-authz-member-a@example.com");
        outsiderId = insertUser("cl-authz-outsider@example.com");

        // 注意: このクラスは com.mannschaft.app.membership パッケージに属するため、無修飾 ScopeType は
        // 同パッケージの com.mannschaft.app.membership.ScopeType（拠点entity用）に解決される。
        // MembershipTestHelper.insertMembership は F00.5系 com.mannschaft.app.membership.domain.ScopeType を
        // 要求するため、ここは完全修飾する（import追加はentity builderのScopeTypeと衝突するため不可）。
        MembershipTestHelper.insertMembership(em, adminAId, com.mannschaft.app.membership.domain.ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminBId, com.mannschaft.app.membership.domain.ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberAId, com.mannschaft.app.membership.domain.ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        locationA = locationRepository.save(CheckinLocationEntity.builder()
                .scopeType(ScopeType.TEAM).scopeId(teamAId).name("CL認可拠点A")
                .locationCode("cl-authz-loc-" + System.nanoTime()).locationSecret("cl-authz-secret")
                .isActive(true).autoCompleteReservation(true).createdBy(adminAId).build());

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET .../checkin-locations（getLocations・checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. 拠点一覧(getLocations)")
    class GetLocations {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/checkin-locations", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMIN（越境）は403")
        void 他チームADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/checkin-locations", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当メンバー(ADMIN不要)は200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/checkin-locations", teamAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].name").value("CL認可拠点A"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST .../checkin-locations（createLocation・checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. 拠点作成(createLocation)")
    class CreateLocation {

        @Test
        @DisplayName("非ADMINメンバーの作成は403")
        void 非ADMINの作成は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/checkin-locations", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createLocationBody("新拠点"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMIN（越境）の作成は403")
        void 他チームADMINの作成は403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/checkin-locations", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createLocationBody("新拠点"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの作成は201")
        void 正当ADMINの作成は201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/checkin-locations", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createLocationBody("新拠点"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.name").value("新拠点"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. PUT .../checkin-locations/{id}（updateLocation・checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. 拠点更新(updateLocation)")
    class UpdateLocation {

        @Test
        @DisplayName("非ADMINメンバーの更新は403")
        void 非ADMINの更新は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/checkin-locations/{id}", teamAId, locationA.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateLocationBody("乗っ取り更新"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("越境ID: 他チームADMINが自チームpathで他チーム拠点IDを更新すると404（既存WHERE突合）")
        void 越境IDは404() throws Exception {
            setAuth(adminBId);
            // adminBは自分のteamB pathを使う(checkAdminOrAboveは通る)が、locationAはteamA所属のため
            // findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull がヒットせずMEMBERSHIP_019
            mockMvc.perform(put("/api/v1/teams/{teamId}/checkin-locations/{id}", teamBId, locationA.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateLocationBody("乗っ取り更新"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_019"));
        }

        @Test
        @DisplayName("正当ADMINの更新は200")
        void 正当ADMINの更新は200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/checkin-locations/{id}", teamAId, locationA.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateLocationBody("正規更新"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("正規更新"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. DELETE .../checkin-locations/{id}（deleteLocation・checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. 拠点削除(deleteLocation)")
    class DeleteLocation {

        @Test
        @DisplayName("非ADMINメンバーの削除は403")
        void 非ADMINの削除は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/checkin-locations/{id}", teamAId, locationA.getId()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("越境ID: 他チームADMINが自チームpathで他チーム拠点IDを削除すると404（既存WHERE突合）")
        void 越境IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/checkin-locations/{id}", teamBId, locationA.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_019"));
        }

        @Test
        @DisplayName("正当ADMINの削除は200")
        void 正当ADMINの削除は200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/checkin-locations/{id}", teamAId, locationA.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.deletedAt").exists());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. GET .../checkin-locations/{id}/qr（getLocationQr・checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. 拠点QR取得(getLocationQr)")
    class GetLocationQr {

        @Test
        @DisplayName("非ADMINメンバーのQR取得は403")
        void 非ADMINのQR取得は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/checkin-locations/{id}/qr", teamAId, locationA.getId()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("越境ID: 他チームADMINが自チームpathで他チーム拠点QRを取得すると404（既存WHERE突合）")
        void 越境IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/checkin-locations/{id}/qr", teamBId, locationA.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_019"));
        }

        @Test
        @DisplayName("正当ADMINのQR取得は200")
        void 正当ADMINのQR取得は200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/checkin-locations/{id}/qr", teamAId, locationA.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.qrToken").exists());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. GET .../checkins/stats（CheckinStatsService#getStats・checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. チェックイン統計(getStats)")
    class GetStats {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/checkins/stats", teamAId)
                            .param("from", "2026-01-01")
                            .param("to", "2026-01-31"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/checkins/stats", teamAId)
                            .param("from", "2026-01-01")
                            .param("to", "2026-01-31"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMIN（越境）は403")
        void 他チームADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/checkins/stats", teamAId)
                            .param("from", "2026-01-01")
                            .param("to", "2026-01-31"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/checkins/stats", teamAId)
                            .param("from", "2026-01-01")
                            .param("to", "2026-01-31"))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Map<String, Object> createLocationBody(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("autoCompleteReservation", true);
        return body;
    }

    private Map<String, Object> updateLocationBody(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("isActive", true);
        body.put("autoCompleteReservation", true);
        return body;
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
                                + "VALUES (:email, 'CL契約', 'テスト', 'CL契約テスト', 'ACTIVE', "
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
                                + "CONCAT('cl-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
