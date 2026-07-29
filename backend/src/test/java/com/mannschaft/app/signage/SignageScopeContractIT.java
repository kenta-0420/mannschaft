package com.mannschaft.app.signage;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave7: signage（デジタルサイネージ）ドメインの参照系 API 契約テスト（試練）。
 *
 * <p>正本: {@code SignageScreenService}/{@code SignageSlotService}/{@code SignageEmergencyService}。
 * {@code getScreen}/{@code listScreens}/{@code listSlots} はサイネージ端末向けトークン認証経路
 * （{@code SignageDisplayController}）と共有されるため元のメソッドは温存し、認証ユーザー向けの
 * {@code *ForActor} オーバーロードで {@code AccessControlService#checkMembership} を敷いた。
 * {@code listEmergencyMessages} は端末経路と共有されないため直接敷いた。書込系
 * （create/update/delete/addSlot/broadcastEmergency）は既に {@code checkAdminOrAbove} で
 * 保護済み（今回のスコープ外）。</p>
 *
 * <p>金型: {@code CmsSeriesTagScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("signage ドメイン 参照系 API 契約テスト（認可根治 Wave7）")
class SignageScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long adminAId;
    private Long memberAId;
    private Long outsiderId;

    private Long screenId;

    @BeforeEach
    void setUp() throws Exception {
        insertRoleIfAbsent("ADMIN", "管理者", 2);

        teamAId = insertTeam("SIGNAGE-STG認可契約チームA");

        adminAId = insertUser("signage-stg-authz-admin-a@example.com");
        memberAId = insertUser("signage-stg-authz-member-a@example.com");
        outsiderId = insertUser("signage-stg-authz-outsider@example.com");

        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        em.flush();
        em.clear();

        screenId = createScreenAsAdminA();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 画面取得(getScreen)・一覧(listScreens)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("画面取得(getScreen)・一覧(listScreens)")
    class ScreenRead {

        @Test
        @DisplayName("非会員の画面取得は403(他チームの画面設定閲覧禁止)")
        void 非会員の画面取得は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/signage/screens/{id}", screenId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)の画面取得は200")
        void 一般メンバーの画面取得は200() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/signage/screens/{id}", screenId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(screenId));
        }

        @Test
        @DisplayName("非会員の画面一覧取得は403")
        void 非会員の画面一覧取得は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/signage/screens")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)の画面一覧取得は200")
        void 一般メンバーの画面一覧取得は200() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/signage/screens")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // スロット一覧(listSlots)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("スロット一覧(listSlots)")
    class SlotRead {

        @Test
        @DisplayName("非会員のスロット一覧取得は403")
        void 非会員のスロット一覧取得は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/signage/screens/{screenId}/slots", screenId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)のスロット一覧取得は200")
        void 一般メンバーのスロット一覧取得は200() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/signage/screens/{screenId}/slots", screenId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 緊急メッセージ履歴一覧(listEmergencyMessages)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("緊急メッセージ履歴一覧(listEmergencyMessages)")
    class EmergencyRead {

        @Test
        @DisplayName("非会員の緊急メッセージ履歴取得は403")
        void 非会員の緊急メッセージ履歴取得は403() throws Exception {
            broadcastEmergencyAsAdminA();

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/signage/screens/{id}/emergency", screenId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)の緊急メッセージ履歴取得は200")
        void 一般メンバーの緊急メッセージ履歴取得は200() throws Exception {
            broadcastEmergencyAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/signage/screens/{id}/emergency", screenId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** adminA の認証コンテキストでチームAの画面を1件作成し、そのIDを返す。 */
    private Long createScreenAsAdminA() throws Exception {
        setAuthentication(adminAId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", "TEAM");
        body.put("scopeId", teamAId);
        body.put("name", "認可契約テスト画面 " + System.nanoTime());
        String resp = mockMvc.perform(post("/api/v1/signage/screens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /** adminA の認証コンテキストで screenId に緊急メッセージを1件配信する。 */
    private void broadcastEmergencyAsAdminA() throws Exception {
        setAuthentication(adminAId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "認可契約テスト緊急連絡");
        mockMvc.perform(post("/api/v1/signage/screens/{id}/emergency", screenId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
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
                                + "VALUES (:email, 'SIGNAGE-STG契約', 'テスト', 'SIGNAGE-STG契約テスト', 'ACTIVE', "
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
                                + "CONCAT('signage-stg-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
