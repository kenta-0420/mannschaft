package com.mannschaft.app.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleEventCategoryEntity;
import com.mannschaft.app.schedule.entity.UserIcalTokenEntity;
import com.mannschaft.app.schedule.repository.ScheduleEventCategoryRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.repository.UserIcalTokenRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave6 追加戦 — 行事カテゴリ（{@code EventCategoryCommonController} /
 * {@code TeamEventCategoryController} / {@code OrgEventCategoryController}）と
 * iCal フィード配信（{@code IcalController} / {@code IcalService}）の API 契約テスト（試練）。
 *
 * <p>変更前の状態に関する詳細はマージ後に戦役台帳へ記録する。本テストは
 * <b>敷設後の仕様</b>を固定する:</p>
 * <ul>
 *   <li>カテゴリの更新・削除は、<b>カテゴリ実体由来のスコープ</b>の ADMIN/DEPUTY_ADMIN のみ（それ以外は 403）</li>
 *   <li>カテゴリ一覧は当該スコープの所属者のみ（それ以外は 403）</li>
 *   <li>スコープ指定 iCal フィードは、<b>トークン所有者</b>が当該スコープに所属する場合のみ配信</li>
 *   <li><b>正常系</b>（正当 ADMIN の更新・削除、メンバーの一覧、自分のフィード購読）を必ず固定する</li>
 * </ul>
 *
 * <p>金型: {@code ScheduleWriteScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL + 手動 SecurityContext +
 * {@code MembershipTestHelper}）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("行事カテゴリ・iCalフィード 認可契約テスト（試練）")
class EventCategoryAndIcalScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScheduleEventCategoryRepository categoryRepository;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private UserIcalTokenRepository icalTokenRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long orgAId;
    private Long orgBId;

    private Long adminTeamAId;   // TEAM A の ADMIN（正当）
    private Long adminTeamBId;   // TEAM B の ADMIN（別 scope の越境者）
    private Long memberTeamAId;  // TEAM A の非 ADMIN メンバー
    private Long adminOrgAId;    // ORG A の ADMIN（正当）
    private Long adminOrgBId;    // ORG B の ADMIN（別 scope の越境者）
    private Long memberOrgAId;   // ORG A の非 ADMIN メンバー
    private Long outsiderId;     // どこにも所属しない非メンバー

    private Long teamCategoryAId;
    private Long orgCategoryAId;

    private static final String TOKEN_MEMBER_TEAM_A = "w6-ical-token-member-team-a";
    private static final String TOKEN_OUTSIDER = "w6-ical-token-outsider";

    @BeforeEach
    void setUp() {
        // test profile は Flyway 無効のため roles マスタが未投入。優先度つきで明示 seed する。
        seedRole("SYSTEM_ADMIN", 1);
        seedRole("ADMIN", 10);
        seedRole("DEPUTY_ADMIN", 20);
        seedRole("MEMBER", 30);
        seedRole("SUPPORTER", 40);
        seedRole("GUEST", 50);

        long nano = System.nanoTime();
        teamAId = insertTeam("W6EC チームA", "w6ec-team-a-" + nano);
        teamBId = insertTeam("W6EC チームB", "w6ec-team-b-" + nano);
        orgAId = insertOrganization("W6EC 組織A", "w6ec-org-a-" + nano);
        orgBId = insertOrganization("W6EC 組織B", "w6ec-org-b-" + nano);

        adminTeamAId = insertUser("w6ec-admin-team-a@example.com");
        adminTeamBId = insertUser("w6ec-admin-team-b@example.com");
        memberTeamAId = insertUser("w6ec-member-team-a@example.com");
        adminOrgAId = insertUser("w6ec-admin-org-a@example.com");
        adminOrgBId = insertUser("w6ec-admin-org-b@example.com");
        memberOrgAId = insertUser("w6ec-member-org-a@example.com");
        outsiderId = insertUser("w6ec-outsider@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため両方張る。
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, memberTeamAId, "MEMBER", teamAId, null);

        MembershipTestHelper.insertMembership(em, adminOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, adminOrgBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgBId, "ADMIN", null, orgBId);
        MembershipTestHelper.insertMembership(em, memberOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, memberOrgAId, "MEMBER", null, orgAId);
        // outsiderId はどこにも所属させない。

        teamCategoryAId = categoryRepository.save(ScheduleEventCategoryEntity.builder()
                .teamId(teamAId)
                .name("W6EC チームA行事")
                .color("#EF4444")
                .isDayOffCategory(false)
                .sortOrder(1)
                .build()).getId();

        orgCategoryAId = categoryRepository.save(ScheduleEventCategoryEntity.builder()
                .organizationId(orgAId)
                .name("W6EC 組織A行事")
                .color("#10B981")
                .isDayOffCategory(false)
                .sortOrder(1)
                .build()).getId();

        // iCal フィードの越境検証用に TEAM A / ORG A の予定を作る
        scheduleRepository.save(ScheduleEntity.builder()
                .teamId(teamAId)
                .title("W6EC チームA練習")
                .startAt(LocalDateTime.now().plusDays(1))
                .endAt(LocalDateTime.now().plusDays(1).plusHours(2))
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .createdBy(adminTeamAId)
                .build());
        scheduleRepository.save(ScheduleEntity.builder()
                .organizationId(orgAId)
                .title("W6EC 組織Aイベント")
                .startAt(LocalDateTime.now().plusDays(2))
                .endAt(LocalDateTime.now().plusDays(2).plusHours(2))
                .eventType(EventType.EVENT)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .createdBy(adminOrgAId)
                .build());

        icalTokenRepository.save(UserIcalTokenEntity.builder()
                .userId(memberTeamAId)
                .token(TOKEN_MEMBER_TEAM_A)
                .isActive(true)
                .build());
        icalTokenRepository.save(UserIcalTokenEntity.builder()
                .userId(outsiderId)
                .token(TOKEN_OUTSIDER)
                .isActive(true)
                .build());

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. PATCH /api/v1/event-categories/{categoryId}
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. PATCH /api/v1/event-categories/{categoryId}")
    class UpdateCategory {

        @Test
        @DisplayName("チームカテゴリ更新: 別scope ADMIN（teamBのADMIN）は403")
        void チームカテゴリ更新_別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            performPatch(teamCategoryAId).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("チームカテゴリ更新: 非ADMINメンバーは403")
        void チームカテゴリ更新_非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            performPatch(teamCategoryAId).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("チームカテゴリ更新: 非メンバーは403")
        void チームカテゴリ更新_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            performPatch(teamCategoryAId).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("チームカテゴリ更新: 正当ADMINは200（正常系）")
        void チームカテゴリ更新_正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            performPatch(teamCategoryAId).andExpect(status().isOk());
        }

        @Test
        @DisplayName("組織カテゴリ更新: 別scope ADMIN（orgBのADMIN）は403")
        void 組織カテゴリ更新_別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            performPatch(orgCategoryAId).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("組織カテゴリ更新: 非ADMINメンバーは403")
        void 組織カテゴリ更新_非ADMINメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            performPatch(orgCategoryAId).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("組織カテゴリ更新: 正当ADMINは200（正常系）")
        void 組織カテゴリ更新_正当ADMINは200() throws Exception {
            setAuth(adminOrgAId);
            performPatch(orgCategoryAId).andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. DELETE /api/v1/event-categories/{categoryId}
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. DELETE /api/v1/event-categories/{categoryId}")
    class DeleteCategory {

        @Test
        @DisplayName("チームカテゴリ削除: 別scope ADMIN（teamBのADMIN）は403")
        void チームカテゴリ削除_別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/event-categories/{categoryId}", teamCategoryAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("チームカテゴリ削除: 非ADMINメンバーは403")
        void チームカテゴリ削除_非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/event-categories/{categoryId}", teamCategoryAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("チームカテゴリ削除: 正当ADMINは204（正常系）")
        void チームカテゴリ削除_正当ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/event-categories/{categoryId}", teamCategoryAId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("組織カテゴリ削除: 別scope ADMIN（orgBのADMIN）は403")
        void 組織カテゴリ削除_別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(delete("/api/v1/event-categories/{categoryId}", orgCategoryAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("組織カテゴリ削除: 正当ADMINは204（正常系）")
        void 組織カテゴリ削除_正当ADMINは204() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(delete("/api/v1/event-categories/{categoryId}", orgCategoryAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. GET /api/v1/teams|organizations/{id}/event-categories（一覧）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. GET /api/v1/teams|organizations/{id}/event-categories（一覧）")
    class ListCategories {

        @Test
        @DisplayName("チーム一覧: 非メンバーは403")
        void チーム一覧_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/event-categories", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("チーム一覧: 別チームのADMINは403")
        void チーム一覧_別チームADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/event-categories", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("チーム一覧: メンバーは200（正常系）")
        void チーム一覧_メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/event-categories", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("組織一覧: 非メンバーは403")
        void 組織一覧_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/event-categories", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("組織一覧: 別組織のADMINは403")
        void 組織一覧_別組織ADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/event-categories", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("組織一覧: メンバーは200（正常系）")
        void 組織一覧_メンバーは200() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/event-categories", orgAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. GET /ical/{token}.ics（スコープ指定フィード）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. GET /ical/{token}.ics（スコープ指定フィード）")
    class IcalFeed {

        @Test
        @DisplayName("teamスコープ: 非所属トークン所有者は403")
        void teamスコープ_非所属は403() throws Exception {
            mockMvc.perform(get("/ical/{token}.ics", TOKEN_OUTSIDER)
                            .param("scope", "team")
                            .param("id", String.valueOf(teamAId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("teamスコープ: 所属メンバーのトークンは200（正常系）")
        void teamスコープ_所属メンバーは200() throws Exception {
            mockMvc.perform(get("/ical/{token}.ics", TOKEN_MEMBER_TEAM_A)
                            .param("scope", "team")
                            .param("id", String.valueOf(teamAId)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("organizationスコープ: 非所属トークン所有者は403")
        void organizationスコープ_非所属は403() throws Exception {
            mockMvc.perform(get("/ical/{token}.ics", TOKEN_OUTSIDER)
                            .param("scope", "organization")
                            .param("id", String.valueOf(orgAId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("organizationスコープ: 所属外チームメンバーのトークンも403（越境封鎖）")
        void organizationスコープ_所属外チームメンバーは403() throws Exception {
            mockMvc.perform(get("/ical/{token}.ics", TOKEN_MEMBER_TEAM_A)
                            .param("scope", "organization")
                            .param("id", String.valueOf(orgAId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("スコープ未指定: 自分のフィードは200（正常系・非回帰）")
        void スコープ未指定_自分のフィードは200() throws Exception {
            mockMvc.perform(get("/ical/{token}.ics", TOKEN_MEMBER_TEAM_A))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("personalスコープ: 自分のフィードは200（正常系・非回帰）")
        void personalスコープ_自分のフィードは200() throws Exception {
            mockMvc.perform(get("/ical/{token}.ics", TOKEN_MEMBER_TEAM_A)
                            .param("scope", "personal"))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private org.springframework.test.web.servlet.ResultActions performPatch(Long categoryId) throws Exception {
        // @Valid は認可より先に走るため、403 を期待するケースでも body は妥当な値にする
        Map<String, Object> body = Map.of("name", "W6EC 改名後");
        return mockMvc.perform(patch("/api/v1/event-categories/{categoryId}", categoryId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private void seedRole(String name, int priority) {
        try {
            em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                    .setParameter("name", name)
                    .getSingleResult();
        } catch (NoResultException e) {
            em.createNativeQuery(
                            "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                    + "VALUES (:name, :name, :priority, 0, NOW(), NOW())")
                    .setParameter("name", name)
                    .setParameter("priority", priority)
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
                                + "VALUES (:email, 'W6EC', 'テスト', 'W6EC テスト', 'ACTIVE', "
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

    private Long insertTeam(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }
}
