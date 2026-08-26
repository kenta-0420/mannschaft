package com.mannschaft.app.admin;

import com.mannschaft.app.admin.entity.FeedbackSubmissionEntity;
import com.mannschaft.app.admin.repository.FeedbackSubmissionRepository;
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

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave5 — admin 系「未回収エンドポイント」認可契約テスト。
 *
 * <p>{@code SecurityConfig} の {@code hasRole} リストに未登録だったため
 * {@code .anyRequest().authenticated()} に落ち、<b>任意の認証済み一般ユーザーが到達できていた</b>
 * 13 エンドポイントを回収したことを機械的に担保する。</p>
 *
 * <h2>認可モデル（コード自体が切り分けを示す）</h2>
 * <ul>
 *   <li><b>システム級 EP</b>（scope 引数を一切持たない＝全テナント横断）:
 *       {@code seals} 2EP / {@code action-templates} 4EP / {@code notifications/stats} 1EP。
 *       {@code SecurityConfig} のパス単位 {@code hasRole("SYSTEM_ADMIN")} へ格上げし、
 *       各 Controller 入口の {@code accessControlService.checkSystemAdmin} と二重防御にする。</li>
 *   <li><b>per-scope EP</b>（scope 引数を持つ）:
 *       {@code dashboard} 2EP / {@code permission-groups} GET 1EP / {@code feedbacks} 3EP。
 *       Controller の public 入口で {@code checkAdminOrAbove(userId, scopeId, scopeType)}。
 *       このうち {@code feedbacks} の {@code respond}/{@code status} は ID のみを引数に取るため
 *       <b>entity 由来の scope</b> で認可し、越境は 404（{@code ADMIN_FB_003}）で存在秘匿する（BOLA 対策）。</li>
 * </ul>
 *
 * <h2>本テストが検証するもの</h2>
 * <p>{@code @AutoConfigureMockMvc(addFilters = false)} のためフィルタチェーン
 * （＝{@code SecurityConfig} のパス単位 {@code hasRole}）は<b>働かない</b>。
 * したがって本テストが検証するのは <b>二重防御のうち Controller 入口側</b>
 * （{@code checkSystemAdmin} / {@code checkAdminOrAbove}）である。
 * これは意図的で、フィルタ設定を将来誤って外しても Controller 側が単独で守れることを保証する。</p>
 *
 * <p>金型は {@code ParkingScopeContractIT} / {@code FacilityOrgScopeContractIT} を踏襲。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("admin 系 未回収エンドポイント 認可契約テスト（Wave5）")
class AdminUnrecoveredScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeedbackSubmissionRepository feedbackRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    private Long systemAdminId; // プラットフォーム SYSTEM_ADMIN
    private Long adminAId;      // teamA の ADMIN（正当）
    private Long adminBId;      // teamB の ADMIN（越境テスト用）
    private Long memberAId;     // teamA の非 ADMIN メンバー
    private Long outsiderId;    // どこにも所属しない非メンバー

    private Long feedbackAId;   // teamA のフィードバック
    private Long feedbackBId;   // teamB のフィードバック（越境テスト用）
    private Long generalFeedbackId; // GENERAL スコープ（scopeId=null・system-admin 管轄）

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("ADMUNREC チームA");
        teamBId = insertTeam("ADMUNREC チームB");

        systemAdminId = insertUser("admunrec-sysadmin@example.com");
        adminAId = insertUser("admunrec-admin-a@example.com");
        adminBId = insertUser("admunrec-admin-b@example.com");
        memberAId = insertUser("admunrec-member-a@example.com");
        outsiderId = insertUser("admunrec-outsider@example.com");

        // checkAdminOrAbove（user_roles）と membership 判定（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（Wave 踏襲の既知の地雷）。
        // TEAM では insertUserRole の team_id=teamId / organization_id=null で発番する。
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // SYSTEM_ADMIN はプラットフォームレベル割当（team_id / organization_id ともに null）。
        MembershipTestHelper.insertUserRole(em, systemAdminId, "SYSTEM_ADMIN", null, null);
        // outsiderId はどこにも所属させない。

        feedbackAId = insertFeedback("TEAM", teamAId, memberAId, "ADMUNREC 要望A");
        feedbackBId = insertFeedback("TEAM", teamBId, memberAId, "ADMUNREC 要望B");
        generalFeedbackId = insertFeedback("GENERAL", null, memberAId, "ADMUNREC 全体要望");
    }

    // ═════════════════════════════════════════════════════════════════════
    // ① seals（システム級・SYSTEM_ADMIN 格上げ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("① /admin/seals（システム級・checkSystemAdmin）")
    class Seals {

        @Test
        @DisplayName("一般認証ユーザーの全印鑑一覧は403")
        void 一般ユーザーの一覧は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/admin/seals")).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("スコープADMINでも（システム級のため）全印鑑一覧は403")
        void スコープADMINでも403() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/admin/seals")).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("SYSTEM_ADMINは200")
        void システム管理者は200() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(get("/api/v1/admin/seals")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("一般認証ユーザーの一括再生成は403")
        void 一般ユーザーの一括再生成は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/admin/seals/regenerate")).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("SYSTEM_ADMINの一括再生成は200")
        void システム管理者の一括再生成は200() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(post("/api/v1/admin/seals/regenerate")).andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ② action-templates（システム級・SYSTEM_ADMIN 格上げ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("② /admin/action-templates（システム級・checkSystemAdmin）")
    class ActionTemplates {

        private static final String BASE = "/api/v1/admin/action-templates";

        /** @Valid は認可より先に走るため、403 期待でも必須項目を充足させる。 */
        private static final String VALID_CREATE_BODY = """
                {
                  "name": "ADMUNREC テンプレート",
                  "actionType": "WARNING",
                  "reason": "規約違反",
                  "templateText": "ご注意ください",
                  "isDefault": false
                }
                """;

        @Test
        @DisplayName("一般認証ユーザーの一覧は403")
        void 一般ユーザーの一覧は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(BASE)).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("スコープADMINでも一覧は403")
        void スコープADMINでも403() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get(BASE)).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("SYSTEM_ADMINの一覧は200")
        void システム管理者の一覧は200() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(get(BASE)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("一般認証ユーザーの作成は403（bodyは妥当・@Valid先行400を回避）")
        void 一般ユーザーの作成は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post(BASE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_CREATE_BODY))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("SYSTEM_ADMINの作成は201")
        void システム管理者の作成は201() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(post(BASE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_CREATE_BODY))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("一般認証ユーザーの更新は403（bodyは妥当）")
        void 一般ユーザーの更新は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(put(BASE + "/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_CREATE_BODY))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("一般認証ユーザーの削除は403")
        void 一般ユーザーの削除は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(delete(BASE + "/1")).andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ⑥ notifications/stats（システム級・SYSTEM_ADMIN 格上げ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("⑥ /admin/notifications/stats（システム級・checkSystemAdmin）")
    class NotificationStats {

        private static final String PATH = "/api/v1/admin/notifications/stats";

        @Test
        @DisplayName("一般認証ユーザーは403")
        void 一般ユーザーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(PATH)).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("スコープADMINでも403")
        void スコープADMINでも403() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get(PATH)).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("SYSTEM_ADMINは200")
        void システム管理者は200() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(get(PATH)).andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ④ dashboard（per-scope・checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("④ /admin/dashboard（per-scope・checkAdminOrAbove）")
    class Dashboard {

        private static final String BASE = "/api/v1/admin/dashboard";

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(BASE).param("scopeType", "TEAM").param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(BASE).param("scopeType", "TEAM").param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別スコープADMINによる越境は403")
        void 別スコープADMINの越境は403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get(BASE).param("scopeType", "TEAM").param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当スコープADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get(BASE).param("scopeType", "TEAM").param("scopeId", teamAId.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("ユーザー一覧: 非メンバーは403")
        void ユーザー一覧_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(BASE + "/users").param("scopeType", "TEAM").param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("ユーザー一覧: 別スコープADMINによる越境は403")
        void ユーザー一覧_別スコープADMINの越境は403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get(BASE + "/users").param("scopeType", "TEAM").param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("ユーザー一覧: 正当スコープADMINは200")
        void ユーザー一覧_正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get(BASE + "/users").param("scopeType", "TEAM").param("scopeId", teamAId.toString()))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ⑤ permission-groups GET（per-scope・checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("⑤ GET /admin/permission-groups（per-scope・checkAdminOrAbove）")
    class PermissionGroups {

        private static final String BASE = "/api/v1/admin/permission-groups";

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(BASE).param("scopeType", "TEAM").param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(BASE).param("scopeType", "TEAM").param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別スコープADMINによる越境は403")
        void 別スコープADMINの越境は403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get(BASE).param("scopeType", "TEAM").param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当スコープADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get(BASE).param("scopeType", "TEAM").param("scopeId", teamAId.toString()))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ③ feedbacks（per-scope・一覧は宣言scope / respond・status は entity 由来 scope）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("③ /admin/feedbacks 一覧（per-scope・checkAdminOrAbove）")
    class FeedbackList {

        private static final String BASE = "/api/v1/admin/feedbacks";

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(BASE).param("scopeType", "TEAM").param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(BASE).param("scopeType", "TEAM").param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別スコープADMINによる越境は403")
        void 別スコープADMINの越境は403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get(BASE).param("scopeType", "TEAM").param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当スコープADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get(BASE).param("scopeType", "TEAM").param("scopeId", teamAId.toString()))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("③ /admin/feedbacks/{id}/respond・status（entity由来scope・越境は404秘匿）")
    class FeedbackMutation {

        private static final String BASE = "/api/v1/admin/feedbacks";

        /** @Valid は認可より先に走るため、403/404 期待でも必須項目を充足させる。 */
        private static final String RESPOND_BODY = """
                { "adminResponse": "対応しました", "isPublicResponse": false }
                """;
        private static final String STATUS_BODY = """
                { "status": "IN_PROGRESS" }
                """;

        @Test
        @DisplayName("respond: 別スコープADMINによる越境IDは404（存在秘匿）")
        void respond_越境は404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(patch(BASE + "/" + feedbackAId + "/respond")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(RESPOND_BODY))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("respond: 非ADMINメンバーは404（存在秘匿）")
        void respond_非ADMINは404() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(patch(BASE + "/" + feedbackAId + "/respond")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(RESPOND_BODY))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("respond: GENERALスコープ（system-admin管轄）はスコープADMINから404")
        void respond_GENERALは404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch(BASE + "/" + generalFeedbackId + "/respond")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(RESPOND_BODY))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("respond: 正当スコープADMINは200")
        void respond_正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch(BASE + "/" + feedbackAId + "/respond")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(RESPOND_BODY))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("status: 別スコープADMINによる越境IDは404（存在秘匿）")
        void status_越境は404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch(BASE + "/" + feedbackBId + "/status")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(STATUS_BODY))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("status: 非メンバーは404（存在秘匿）")
        void status_非メンバーは404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch(BASE + "/" + feedbackAId + "/status")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(STATUS_BODY))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("status: 正当スコープADMINは200")
        void status_正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch(BASE + "/" + feedbackAId + "/status")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(STATUS_BODY))
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

    private Long insertFeedback(String scopeType, Long scopeId, Long submittedBy, String title) {
        return feedbackRepository.save(FeedbackSubmissionEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .category("REQUEST")
                .title(title)
                .body("ADMUNREC 本文")
                .submittedBy(submittedBy)
                .status(FeedbackStatus.OPEN)
                .build()).getId();
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
                                + "VALUES (:email, 'ADMUNREC', 'テスト', 'ADMUNREC テスト', 'ACTIVE', "
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
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
