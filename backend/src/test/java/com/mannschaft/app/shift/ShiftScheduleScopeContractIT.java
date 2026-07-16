package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
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

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave3-B6 — shift ドメイン（{@code ShiftScheduleController}/{@code ShiftScheduleService}）
 * 書込API契約テスト（試練 / red 先行）。
 *
 * <p>正本: {@code .claude/campaigns/2026-07-10-authz-idor-audit.md} Wave3-B6 節。
 * createSchedule / updateSchedule / deleteSchedule / transitionStatus(PUBLISH等) / duplicateSchedule
 * が全て認可ゼロだった欠陥を是正する（{@code ShiftScheduleService} 実装参照）。</p>
 *
 * <p>金型: {@code TimetableScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。
 * {@code ShiftScheduleController} は path に teamId を持たず、create は
 * {@code @RequestParam Long teamId}、update/delete/transition/duplicate は scheduleId のみで
 * 操作するため、「別 scope ADMIN が scheduleId を直接指定して越境する」BOLA を重点的に検証する。</p>
 *
 * <p><b>象限</b>: 非ADMINメンバー（teamAの一般メンバー）/ 別scope ADMIN（BOLA: teamBのADMINが
 * teamAのシフトへアクセス）/ 正当ADMIN（teamAのADMIN）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("shift ドメイン（シフトスケジュール）認可契約テスト（試練）")
class ShiftScheduleScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShiftScheduleRepository scheduleRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    private Long adminTeamAId;   // TEAM A の ADMIN（正当）
    private Long adminTeamBId;   // TEAM B の ADMIN（別 scope の越境攻撃者）
    private Long memberTeamAId;  // TEAM A の非 ADMIN メンバー

    private Long scheduleAId;    // TEAM A のシフトスケジュール（DRAFT）

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("WAVE3B6 チームA");
        teamBId = insertTeam("WAVE3B6 チームB");

        adminTeamAId = insertUser("wave3b6-shift-admin-team-a@example.com");
        adminTeamBId = insertUser("wave3b6-shift-admin-team-b@example.com");
        memberTeamAId = insertUser("wave3b6-shift-member-team-a@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（RepairPlanAuthorizationMatrixTest 踏襲）。
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        ShiftScheduleEntity scheduleA = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamAId)
                .title("WAVE3B6 3月第1週シフト")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.DRAFT)
                .createdBy(adminTeamAId)
                .build());
        scheduleAId = scheduleA.getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. POST /shifts/schedules?teamId=（作成）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. POST /shifts/schedules?teamId=（作成）")
    class CreateSchedule {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/shifts/schedules").param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/shifts/schedules").param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/shifts/schedules").param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "新規シフトスケジュール");
            body.put("startDate", LocalDate.of(2026, 4, 1).toString());
            body.put("endDate", LocalDate.of(2026, 4, 7).toString());
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. PATCH /shifts/schedules/{id}（更新・entity由来: teamIdがpathに無い★BOLA要警戒★）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. PATCH /shifts/schedules/{id}（更新）")
    class UpdateSchedule {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/shifts/schedules/{id}", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "更新後"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMINがscheduleIdを直接指定）は403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/shifts/schedules/{id}", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "更新後"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/shifts/schedules/{id}", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "更新後"))))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. DELETE /shifts/schedules/{id}（削除・entity由来★BOLA要警戒★）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. DELETE /shifts/schedules/{id}（削除）")
    class DeleteSchedule {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/shifts/schedules/{id}", scheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/shifts/schedules/{id}", scheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/shifts/schedules/{id}", scheduleAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. POST /shifts/schedules/{id}/transition（ステータス遷移・PUBLISH含む★BOLA要警戒★）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. POST /shifts/schedules/{id}/transition（ステータス遷移）")
    class TransitionStatus {

        @Test
        @DisplayName("非ADMINメンバーはPUBLISH遷移403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/transition", scheduleAId)
                            .param("status", "PUBLISHED"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINはPUBLISH遷移403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/transition", scheduleAId)
                            .param("status", "PUBLISHED"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINはPUBLISH遷移200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/transition", scheduleAId)
                            .param("status", "PUBLISHED"))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. POST /shifts/schedules/{id}/duplicate（複製・BOLA: 複製元(source) scope由来）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. POST /shifts/schedules/{id}/duplicate（複製）")
    class DuplicateSchedule {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/duplicate", scheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMINが複製元(source)のscheduleIdを直接指定）は403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/duplicate", scheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/duplicate", scheduleAId))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
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
                                + "VALUES (:email, 'WAVE3B6', 'テスト', 'WAVE3B6 テスト', 'ACTIVE', "
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
