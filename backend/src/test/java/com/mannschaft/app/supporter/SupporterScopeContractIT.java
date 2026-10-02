package com.mannschaft.app.supporter;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave3-B5: supporter ドメイン（{@code TeamController} のサポーター管理 7EP）
 * API 契約テスト（試練）。
 *
 * <p>正本: 依頼文（Wave3-B5 supporter節）・{@code AccessControlService}
 * （{@code checkAdminOrAbove}）。金型: {@code DigestScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL）。</p>
 *
 * <p>対象 7EP（すべて「サポーター管理（管理者向け）」区画 = checkAdminOrAbove 保護）:</p>
 * <ul>
 *   <li>GET /{slug}/supporters（承認済み一覧）</li>
 *   <li>GET /{slug}/supporter-applications（申請一覧）</li>
 *   <li>POST /{slug}/supporter-applications/{id}/approve（個別承認）</li>
 *   <li>POST /{slug}/supporter-applications/{id}/reject（個別却下）</li>
 *   <li>POST /{slug}/supporter-applications/bulk-approve（一括承認）</li>
 *   <li>GET /{slug}/supporter-settings（設定取得）</li>
 *   <li>PUT /{slug}/supporter-settings（設定更新）</li>
 * </ul>
 *
 * <p>BOLA対策: applicationId の scope 不一致は {@code SupporterService.findPendingApplicationOrThrow}
 * が既に SUPPORTER_003（存在秘匿）で保護済み（本 IT はその 404 マッピングも検証する）。</p>
 *
 * <p>認可根治 Wave6 追加戦（{@code FollowGate}）: サポーター自己登録 EP
 * （POST /{slug}/follow）に F00 {@code ContentVisibilityChecker#assertCanView} による
 * 可視性ゲートと {@code TeamService#assertSupporterEnabled} による受け入れ可否ゲートを
 * 敷設したため、その仕様を固定する。unfollow / follow-status の 2EP は
 * 本人の複合キー引き（userId × scope）で完結するセルフサービス操作のため対象外。</p>
 *
 * <p>ADMIN 役の被験者は {@code checkMembership}（memberships 表）と
 * {@code checkAdminOrAbove}（user_roles 表）の両方を満たすよう二重に seed する
 * （認可根治戦役 Wave0+1 で確立した既知の地雷）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("supporter ドメイン API 契約テスト（認可根治 Wave3-B5）")
class SupporterScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private String teamASlug;
    private String teamBSlug;
    private Long teamAId;
    private Long teamBId;
    private Long adminAId;
    private Long adminBId;
    private Long memberAId;
    private Long outsiderId;

    @BeforeEach
    void setUp() {
        insertRoleIfAbsent("ADMIN", "管理者", 2);

        teamAId = insertTeam("SP認可契約チームA");
        teamBId = insertTeam("SP認可契約チームB");
        teamASlug = selectSlug(teamAId);
        teamBSlug = selectSlug(teamBId);

        adminAId = insertUser("sp-authz-admin-a@example.com");
        adminBId = insertUser("sp-authz-admin-b@example.com");
        memberAId = insertUser("sp-authz-member-a@example.com");
        outsiderId = insertUser("sp-authz-outsider@example.com");

        // ADMIN 役は checkMembership(memberships) と checkAdminOrAbove(user_roles) の両方を満たす必要がある
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);

        // memberA はチームAの一般メンバー（ADMIN権限なし）
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        // outsiderId はどちらのチームにも一切所属しない

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 閲覧系（サポーター一覧・申請一覧）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("サポーター一覧(getSupporters)・申請一覧(getSupporterApplications)")
    class ListEndpoints {

        @Test
        @DisplayName("非メンバーのサポーター一覧取得は403")
        void 非メンバーのサポーター一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{slug}/supporters", teamASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)のサポーター一覧取得は403")
        void 一般メンバーのサポーター一覧は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/teams/{slug}/supporters", teamASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINのサポーター一覧取得は200")
        void 正当ADMINのサポーター一覧は200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(get("/api/v1/teams/{slug}/supporters", teamASlug))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバーの申請一覧取得は403")
        void 非メンバーの申請一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{slug}/supporter-applications", teamASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの申請一覧取得は403（越境拒否）")
        void 他チームADMINの申請一覧は403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(get("/api/v1/teams/{slug}/supporter-applications", teamASlug))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINの申請一覧取得は200")
        void 正当ADMINの申請一覧は200() throws Exception {
            insertApplication(teamAId, memberAId, SupporterApplicationStatus.PENDING);
            setAuthentication(adminAId);
            mockMvc.perform(get("/api/v1/teams/{slug}/supporter-applications", teamASlug))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 個別承認(approve)・個別却下(reject)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("個別承認(approve)・個別却下(reject)")
    class ApproveReject {

        @Test
        @DisplayName("非ADMINメンバーの承認は403")
        void 非ADMINメンバーの承認は403() throws Exception {
            Long appId = insertApplication(teamAId, outsiderId, SupporterApplicationStatus.PENDING);
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/teams/{slug}/supporter-applications/{id}/approve",
                            teamASlug, appId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINがチームAの申請IDを直指定して承認すると404（BOLA: SUPPORTER_003存在秘匿）")
        void 他チームADMINの越境承認は404() throws Exception {
            Long appId = insertApplication(teamAId, outsiderId, SupporterApplicationStatus.PENDING);
            setAuthentication(adminBId);
            mockMvc.perform(post("/api/v1/teams/{slug}/supporter-applications/{id}/approve",
                            teamBSlug, appId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SUPPORTER_003"));
        }

        @Test
        @DisplayName("正当ADMINの承認は204")
        void 正当ADMINの承認は204() throws Exception {
            Long appId = insertApplication(teamAId, outsiderId, SupporterApplicationStatus.PENDING);
            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/teams/{slug}/supporter-applications/{id}/approve",
                            teamASlug, appId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("非ADMINメンバーの却下は403")
        void 非ADMINメンバーの却下は403() throws Exception {
            Long appId = insertApplication(teamAId, outsiderId, SupporterApplicationStatus.PENDING);
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/teams/{slug}/supporter-applications/{id}/reject",
                            teamASlug, appId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINがチームAの申請IDを直指定して却下すると404（BOLA）")
        void 他チームADMINの越境却下は404() throws Exception {
            Long appId = insertApplication(teamAId, outsiderId, SupporterApplicationStatus.PENDING);
            setAuthentication(adminBId);
            mockMvc.perform(post("/api/v1/teams/{slug}/supporter-applications/{id}/reject",
                            teamBSlug, appId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SUPPORTER_003"));
        }

        @Test
        @DisplayName("正当ADMINの却下は204")
        void 正当ADMINの却下は204() throws Exception {
            Long appId = insertApplication(teamAId, outsiderId, SupporterApplicationStatus.PENDING);
            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/teams/{slug}/supporter-applications/{id}/reject",
                            teamASlug, appId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 一括承認(bulkApprove)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("一括承認(bulkApprove)")
    class BulkApprove {

        @Test
        @DisplayName("非ADMINメンバーの一括承認は403")
        void 非ADMINメンバーの一括承認は403() throws Exception {
            Long appId = insertApplication(teamAId, outsiderId, SupporterApplicationStatus.PENDING);
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/teams/{slug}/supporter-applications/bulk-approve", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody(appId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINがチームAの申請IDを一括承認しようとすると404（BOLA）")
        void 他チームADMINの越境一括承認は404() throws Exception {
            Long appId = insertApplication(teamAId, outsiderId, SupporterApplicationStatus.PENDING);
            setAuthentication(adminBId);
            mockMvc.perform(post("/api/v1/teams/{slug}/supporter-applications/bulk-approve", teamBSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody(appId))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SUPPORTER_003"));
        }

        @Test
        @DisplayName("正当ADMINの一括承認は204")
        void 正当ADMINの一括承認は204() throws Exception {
            Long appId = insertApplication(teamAId, outsiderId, SupporterApplicationStatus.PENDING);
            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/teams/{slug}/supporter-applications/bulk-approve", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody(appId))))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // サポーター設定(getSupporterSettings/updateSupporterSettings)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("サポーター設定(settings)")
    class Settings {

        @Test
        @DisplayName("非メンバーの設定取得は403")
        void 非メンバーの設定取得は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{slug}/supporter-settings", teamASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)の設定取得は403")
        void 一般メンバーの設定取得は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/teams/{slug}/supporter-settings", teamASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの設定取得は200")
        void 正当ADMINの設定取得は200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(get("/api/v1/teams/{slug}/supporter-settings", teamASlug))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーの設定更新は403")
        void 非ADMINメンバーの設定更新は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(put("/api/v1/teams/{slug}/supporter-settings", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(settingsBody(false))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの越境設定更新は403")
        void 他チームADMINの越境設定更新は403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(put("/api/v1/teams/{slug}/supporter-settings", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(settingsBody(false))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINの設定更新は200")
        void 正当ADMINの設定更新は200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(put("/api/v1/teams/{slug}/supporter-settings", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(settingsBody(false))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.autoApprove").value(false));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // サポーター自己登録（follow）— 認可根治 Wave6 追加戦
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("サポーター自己登録(followTeam)の可視性・受け入れ可否ゲート")
    class FollowGate {

        @Test
        @DisplayName("【正常系】公開チーム(PUBLIC・受け入れ有効)は部外者でもフォローできる(201)")
        void 公開チームは部外者でもフォローできる() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{slug}/follow", teamASlug))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("APPROVED"));
        }

        @Test
        @DisplayName("【正常系】フォロー後の状態取得は APPROVED を返す")
        void フォロー後の状態はAPPROVED() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{slug}/follow", teamASlug))
                    .andExpect(status().isCreated());
            mockMvc.perform(get("/api/v1/teams/{slug}/follow/status", teamASlug))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("APPROVED"));
        }

        @Test
        @DisplayName("非公開チーム(MEMBERS_AND_ABOVE)を部外者がフォローすると403")
        void 非公開チームの部外者フォローは403() throws Exception {
            Long privateTeamId = insertTeamWithVisibility(
                    "SP認可契約チーム非公開", "MEMBERS_AND_ABOVE", true);
            String privateSlug = selectSlug(privateTeamId);
            em.flush();
            em.clear();

            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{slug}/follow", privateSlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("VISIBILITY_001"));
        }

        @Test
        @DisplayName("サポーター受け入れ無効のチームをフォローすると403")
        void 受け入れ無効チームのフォローは403() throws Exception {
            Long disabledTeamId = insertTeamWithVisibility(
                    "SP認可契約チーム受入無効", "PUBLIC", false);
            String disabledSlug = selectSlug(disabledTeamId);
            em.flush();
            em.clear();

            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{slug}/follow", disabledSlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_SUPPORTER_DISABLED"));
        }

        @Test
        @DisplayName("非公開チームでもフォロー状態取得は自分の状態のみで200（正常系を壊さない）")
        void 非公開チームのフォロー状態取得は200() throws Exception {
            Long privateTeamId = insertTeamWithVisibility(
                    "SP認可契約チーム非公開ST", "MEMBERS_AND_ABOVE", true);
            String privateSlug = selectSlug(privateTeamId);
            em.flush();
            em.clear();

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{slug}/follow/status", privateSlug))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("NONE"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Map<String, Object> bulkBody(Long applicationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationIds", List.of(applicationId));
        return body;
    }

    private Map<String, Object> settingsBody(boolean autoApprove) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("autoApprove", autoApprove);
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
                                + "VALUES (:email, 'SP契約', 'テスト', 'SP契約テスト', 'ACTIVE', "
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
                                + "CONCAT('sp-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    /**
     * visibility / supporter_enabled を指定してチームを INSERT する（follow ゲート検証用）。
     *
     * @param name             チーム名（一意）
     * @param visibility       TeamEntity.Visibility の enum 名
     * @param supporterEnabled サポーター受け入れ可否
     */
    private Long insertTeamWithVisibility(String name, String visibility, boolean supporterEnabled) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, :visibility, :se, 0, 0, "
                                + "CONCAT('sp-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("visibility", visibility)
                .setParameter("se", supporterEnabled ? 1 : 0)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private String selectSlug(Long teamId) {
        return (String) em.createNativeQuery("SELECT slug FROM teams WHERE id = :id")
                .setParameter("id", teamId)
                .getSingleResult();
    }

    /**
     * supporter_applications へ 1 行 INSERT する。
     * NOT NULL 列（scope_type/scope_id/user_id/status/created_at/updated_at）をすべて明示する。
     */
    private Long insertApplication(Long teamId, Long applicantUserId, SupporterApplicationStatus status) {
        em.createNativeQuery(
                        "INSERT INTO supporter_applications (scope_type, scope_id, user_id, message, status, "
                                + "created_at, updated_at) "
                                + "VALUES ('TEAM', :sid, :uid, 'テスト申請', :status, NOW(), NOW())")
                .setParameter("sid", teamId)
                .setParameter("uid", applicantUserId)
                .setParameter("status", status.name())
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM supporter_applications")
                .getSingleResult()).longValue();
    }
}
