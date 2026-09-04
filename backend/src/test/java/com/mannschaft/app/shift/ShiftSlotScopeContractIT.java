package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
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
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave6 — shift ドメイン（{@code ShiftSlotController}/{@code ShiftSlotService}）
 * 認可契約テスト（試練 / red 先行）。
 *
 * <p>{@code ShiftSlotService} は {@code AccessControlService} を import すらしておらず、
 * 操作者を受け取る口が無かったため、全 6 エンドポイントが無認可だった
 *（認証さえ通れば任意チームのシフト枠を閲覧・改変・割当できた）。</p>
 *
 * <p>金型: {@code ShiftScheduleScopeContractIT}（Wave3-B6・同ドメイン）。
 * {@code /slots/{slotId}} 系は path に teamId を持たず slot 実体からしか scope を
 * 導出できないため、「別 scope ADMIN が slotId を直接指定して越境する」BOLA を重点検証する。</p>
 *
 * <p><b>象限</b>: 非メンバー（無関係ユーザー）/ 別 scope ADMIN（BOLA）/ 非ADMINメンバー /
 * SUPPORTER（参照不可）/ 正当 ADMIN。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("shift ドメイン（シフト枠）認可契約テスト（試練）")
class ShiftSlotScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShiftScheduleRepository scheduleRepository;

    @Autowired
    private ShiftSlotRepository slotRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    private Long adminTeamAId;      // TEAM A の ADMIN（正当）
    private Long adminTeamBId;      // TEAM B の ADMIN（別 scope の越境攻撃者）
    private Long memberTeamAId;     // TEAM A の非 ADMIN メンバー
    private Long supporterTeamAId;  // TEAM A の SUPPORTER（参照不可）
    private Long outsiderId;        // どのチームにも属さないユーザー

    private Long scheduleAId;       // TEAM A のシフトスケジュール
    private Long slotAId;           // TEAM A のシフト枠

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("WAVE6 シフト枠 チームA");
        teamBId = insertTeam("WAVE6 シフト枠 チームB");

        adminTeamAId = insertUser("wave6-slot-admin-team-a@example.com");
        adminTeamBId = insertUser("wave6-slot-admin-team-b@example.com");
        memberTeamAId = insertUser("wave6-slot-member-team-a@example.com");
        supporterTeamAId = insertUser("wave6-slot-supporter-team-a@example.com");
        outsiderId = insertUser("wave6-slot-outsider@example.com");

        // checkAdminOrAbove（user_roles）と isMember（memberships）は別系統のため
        // ADMIN ユーザーには両方張る（ShiftScheduleScopeContractIT 踏襲）。
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, supporterTeamAId, ScopeType.TEAM, teamAId, RoleKind.SUPPORTER);

        ShiftScheduleEntity scheduleA = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamAId)
                .title("WAVE6 シフト枠テスト用スケジュール")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                // CMP-260826-2127（AC-13）: 本 IT が固定するのは<b>認可契約</b>（誰が触れるか）であり
                // 可視性ではない。未公開シフト表の遮断が入ると DRAFT では一般メンバーの正常系が 404 になり、
                // 「自チームの公開シフトを読める」という日常正常系の番人が消えてしまう。
                // よって期待値でなくフィクスチャを公開済みへ直す（DRAFT に対する 404 は
                // ShiftUnpublishedScheduleVisibilityContractIT が別途固定している）。
                .status(ShiftScheduleStatus.PUBLISHED)
                .publishedAt(java.time.LocalDateTime.of(2026, 2, 20, 10, 0))
                .createdBy(adminTeamAId)
                .build());
        scheduleAId = scheduleA.getId();

        ShiftSlotEntity slotA = slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(scheduleAId)
                .slotDate(LocalDate.of(2026, 3, 2))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .requiredCount(2)
                .build());
        slotAId = slotA.getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /shifts/schedules/{scheduleId}/slots（一覧・参照）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /shifts/schedules/{scheduleId}/slots（一覧）")
    class ListSlots {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/shifts/schedules/{id}/slots", scheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/shifts/schedules/{id}/slots", scheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("SUPPORTERは403（PDF と同じくシフト運用情報は不可）")
        void SUPPORTERは403() throws Exception {
            setAuth(supporterTeamAId);
            mockMvc.perform(get("/api/v1/shifts/schedules/{id}/slots", scheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/shifts/schedules/{id}/slots", scheduleAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/shifts/schedules/{id}/slots", scheduleAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST /shifts/schedules/{scheduleId}/slots（作成）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /shifts/schedules/{scheduleId}/slots（作成）")
    class CreateSlot {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/slots", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(slotBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/slots", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(slotBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/slots", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(slotBody())))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. POST /shifts/schedules/{scheduleId}/slots/bulk（一括作成）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. POST /shifts/schedules/{scheduleId}/slots/bulk（一括作成）")
    class BulkCreateSlots {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/slots/bulk", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("slots", List.of(slotBody())))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/slots/bulk", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("slots", List.of(slotBody())))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/slots/bulk", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("slots", List.of(slotBody())))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. PATCH /shifts/slots/{slotId}（更新・slot実体由来★BOLA要警戒★）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. PATCH /shifts/slots/{slotId}（更新）")
    class UpdateSlot {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/shifts/slots/{id}", slotAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("note", "更新後"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMINがslotIdを直接指定）は403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/shifts/slots/{id}", slotAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("note", "更新後"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/shifts/slots/{id}", slotAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("note", "更新後"))))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. PATCH /shifts/slots/{slotId}/assignments（割当・★人の配置を書き換える★）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. PATCH /shifts/slots/{slotId}/assignments（差分割当）")
    class PatchSlotAssignments {

        @Test
        @DisplayName("非ADMINメンバーは403（自分を勝手に割り当てられない）")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/shifts/slots/{id}/assignments", slotAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignmentBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/shifts/slots/{id}/assignments", slotAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignmentBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/shifts/slots/{id}/assignments", slotAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignmentBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> assignmentBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("addUserIds", List.of(memberTeamAId));
            body.put("removeUserIds", List.of());
            body.put("slotVersion", 0);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. DELETE /shifts/slots/{slotId}（削除・slot実体由来★BOLA要警戒★）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. DELETE /shifts/slots/{slotId}（削除）")
    class DeleteSlot {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/shifts/slots/{id}", slotAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/shifts/slots/{id}", slotAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/shifts/slots/{id}", slotAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> slotBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slotDate", LocalDate.of(2026, 3, 3).toString());
        body.put("startTime", "09:00:00");
        body.put("endTime", "17:00:00");
        body.put("requiredCount", 1);
        return body;
    }

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
                                + "VALUES (:email, 'WAVE6', 'テスト', 'WAVE6 テスト', 'ACTIVE', "
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
