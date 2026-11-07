package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261010-1432 欠陥 P — シフト枠の version 返却と、1 トランザクションの枠間移動 API の契約テスト（試練）。
 *
 * <p>是正前の欠陥: FE は {@code slotVersion=0} を固定送信していた。BE は version 不一致を 409 {@code SHIFT_018}
 * にするが、枠の応答に version が無く FE は最新値を知る術が無かった。また枠 X から Y への「移動」は
 * 削除と追加の 2 回の PATCH で行われ、Y への追加が拒否されると X の割当だけが失われていた。</p>
 *
 * <p>移動 API: {@code POST /api/v1/shifts/slots/{slotId}/assignments/move}。認可・存在オラクル規約は
 * 既存の枠起点 API（{@code PATCH /slots/{slotId}/assignments}）と同じ（越境・不在はどちらも 404 {@code SHIFT_002}、
 * 同チームの非管理者は 403 {@code COMMON_002}）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261010-1432 P: 枠の version 返却・枠間移動 API 契約テスト（試練）")
class ShiftSlotVersionAndMoveContractIT extends AbstractMySqlIntegrationTest {

    private static final String MOVE = "/api/v1/shifts/slots/{slotId}/assignments/move";

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
    private Long adminAId;
    private Long adminBId;
    private Long memberAId;
    private Long supporterAId;
    private Long outsiderId;
    private Long workerId;      // 割り当てられるメンバー
    private Long otherWorkerId;

    private Long scheduleAId;
    private Long otherScheduleAId; // 同じチームの別シフト表
    private Long scheduleBId;      // 別チームのシフト表

    private Long slotX;            // 移動元（worker 割当済み・必要 2）
    private Long slotY;            // 移動先（空き・必要 1）
    private Long slotFull;         // 満員（otherWorker 割当済み・必要 1）
    private Long slotOtherSchedule;
    private Long slotTeamB;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("CMP1432 枠移動 チームA");
        teamBId = insertTeam("CMP1432 枠移動 チームB");
        adminAId = insertUser("cmp1432-slot-admin-a@example.com");
        adminBId = insertUser("cmp1432-slot-admin-b@example.com");
        memberAId = insertUser("cmp1432-slot-member-a@example.com");
        supporterAId = insertUser("cmp1432-slot-supporter-a@example.com");
        outsiderId = insertUser("cmp1432-slot-outsider@example.com");
        workerId = insertUser("cmp1432-slot-worker@example.com");
        otherWorkerId = insertUser("cmp1432-slot-other-worker@example.com");

        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, supporterAId, ScopeType.TEAM, teamAId, RoleKind.SUPPORTER);
        MembershipTestHelper.insertMembership(em, workerId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, otherWorkerId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        scheduleAId = insertSchedule(teamAId);
        otherScheduleAId = insertSchedule(teamAId);
        scheduleBId = insertSchedule(teamBId);

        slotX = insertSlot(scheduleAId, LocalTime.of(9, 0), LocalTime.of(12, 0), 2, List.of(workerId));
        slotY = insertSlot(scheduleAId, LocalTime.of(13, 0), LocalTime.of(17, 0), 1, List.of());
        slotFull = insertSlot(scheduleAId, LocalTime.of(18, 0), LocalTime.of(21, 0), 1, List.of(otherWorkerId));
        slotOtherSchedule = insertSlot(otherScheduleAId, LocalTime.of(13, 0), LocalTime.of(17, 0), 1, List.of());
        slotTeamB = insertSlot(scheduleBId, LocalTime.of(13, 0), LocalTime.of(17, 0), 1, List.of());

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-P0: version の返却
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-P0. 枠の応答に最新 version が入る")
    class VersionInResponse {

        @Test
        @DisplayName("AC-P0: GET 枠一覧の各枠に DB と同じ version が入る")
        void AC_P0_GET一覧にversionが入る() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/shifts/schedules/{id}/slots", scheduleAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.id == " + slotX + ")].version").value(versionOf(slotX).intValue()));
        }

        @Test
        @DisplayName("AC-P0/AC-P1: PATCH 割当の応答は更新後の version を返し、それを使えば追加→削除→追加を続けても409にならない")
        void AC_P0_PATCH割当は更新後versionを返し連続操作で409にならない() throws Exception {
            setAuth(adminAId);
            long v0 = versionOf(slotY);

            int v1 = patchAssignments(slotY, List.of(workerId), List.of(), (int) v0);
            assertThat((long) v1).as("応答の version は DB の最新値").isEqualTo(versionOf(slotY)).isEqualTo(v0 + 1);

            int v2 = patchAssignments(slotY, List.of(), List.of(workerId), v1);
            int v3 = patchAssignments(slotY, List.of(workerId), List.of(), v2);
            assertThat((long) v3).isEqualTo(versionOf(slotY)).isEqualTo(v0 + 3);
        }

        @Test
        @DisplayName("AC-P0: PATCH 枠更新の応答は更新後の version を返す")
        void AC_P0_PATCH枠更新は更新後versionを返す() throws Exception {
            setAuth(adminAId);
            long before = versionOf(slotY);
            MvcResult result = mockMvc.perform(patch("/api/v1/shifts/slots/{id}", slotY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("note", "更新"))))
                    .andExpect(status().isOk())
                    .andReturn();
            int returned = JsonPath.read(result.getResponse().getContentAsString(), "$.data.version");
            assertThat((long) returned).isEqualTo(versionOf(slotY)).isEqualTo(before + 1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 移動 API
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("移動 API（POST /slots/{slotId}/assignments/move）")
    class Move {

        @Test
        @DisplayName("AC-P4: 両枠の現行 version を渡すと1回で移動し、両枠の更新後 version を返す")
        void AC_P4_両枠のversionで移動できる() throws Exception {
            setAuth(adminAId);
            long vx = versionOf(slotX);
            long vy = versionOf(slotY);

            mockMvc.perform(post(MOVE, slotX)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(moveBody(slotY, workerId, vx, vy))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.source.id").value(slotX))
                    .andExpect(jsonPath("$.data.source.assignedUserIds.length()").value(0))
                    .andExpect(jsonPath("$.data.source.version").value((int) vx + 1))
                    .andExpect(jsonPath("$.data.target.id").value(slotY))
                    .andExpect(jsonPath("$.data.target.assignedUserIds[0]").value(workerId))
                    .andExpect(jsonPath("$.data.target.version").value((int) vy + 1));

            em.flush();
            em.clear();
            assertThat(assignedOf(slotX)).doesNotContain(workerId);
            assertThat(assignedOf(slotY)).containsExactly(workerId);
        }

        @Test
        @DisplayName("AC-P3/AC-P7: 移動先が満員なら拒否され、移動元の割当は失われない（両枠とも不変）")
        void AC_P3_移動先が満員なら両枠とも不変() throws Exception {
            setAuth(adminAId);
            Snapshot before = snapshot();

            mockMvc.perform(post(MOVE, slotX)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    moveBody(slotFull, workerId, versionOf(slotX), versionOf(slotFull)))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_017"));

            assertUnchanged(before);
        }

        @Test
        @DisplayName("AC-P7: 移動元の version が古いと409 SHIFT_018で両枠とも不変")
        void AC_P7_移動元version不一致は409で不変() throws Exception {
            setAuth(adminAId);
            Snapshot before = snapshot();

            mockMvc.perform(post(MOVE, slotX)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    moveBody(slotY, workerId, versionOf(slotX) + 5, versionOf(slotY)))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_018"));

            assertUnchanged(before);
        }

        @Test
        @DisplayName("AC-P7: 移動先の version が古いと409 SHIFT_018で両枠とも不変")
        void AC_P7_移動先version不一致は409で不変() throws Exception {
            setAuth(adminAId);
            Snapshot before = snapshot();

            mockMvc.perform(post(MOVE, slotX)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    moveBody(slotY, workerId, versionOf(slotX), versionOf(slotY) + 5))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_018"));

            assertUnchanged(before);
        }

        @Test
        @DisplayName("AC-P7: 移動元に当該ユーザーが居ないと409 SHIFT_046で両枠とも不変")
        void AC_P7_移動元に居ないユーザーは409で不変() throws Exception {
            setAuth(adminAId);
            Snapshot before = snapshot();

            mockMvc.perform(post(MOVE, slotX)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    moveBody(slotY, otherWorkerId, versionOf(slotX), versionOf(slotY)))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_046"));

            assertUnchanged(before);
        }

        @Test
        @DisplayName("AC-P5: 別シフト表（同チーム）・別チームの枠・不在の枠を移動先に混ぜると、いずれも同一の404 SHIFT_002で両枠とも不変")
        void AC_P5_別シフト表別チーム不在の移動先は同一の404() throws Exception {
            setAuth(adminAId);
            Snapshot before = snapshot();

            MvcResult otherSchedule = move(slotX, slotOtherSchedule, workerId);
            MvcResult otherTeam = move(slotX, slotTeamB, workerId);
            MvcResult absent = move(slotX, 999_999_999L, workerId);

            for (MvcResult r : List.of(otherSchedule, otherTeam, absent)) {
                assertThat(r.getResponse().getStatus()).isEqualTo(404);
                assertThat((String) JsonPath.read(r.getResponse().getContentAsString(), "$.error.code"))
                        .isEqualTo("SHIFT_002");
            }
            assertSameError(otherTeam, absent);
            assertSameError(otherSchedule, absent);
            assertUnchanged(before);
            assertThat(assignedOf(slotOtherSchedule)).isEmpty();
            assertThat(assignedOf(slotTeamB)).isEmpty();
        }

        @Test
        @DisplayName("AC-P5: 越境（別チーム ADMIN）が移動元を指定すると、不在の枠と同一の404 SHIFT_002で不変")
        void AC_P5_越境の移動元は不在と同一の404() throws Exception {
            setAuth(adminBId);
            Snapshot before = snapshot();

            MvcResult crossTeam = move(slotX, slotTeamB, workerId);
            MvcResult absent = move(999_999_999L, slotTeamB, workerId);

            assertThat(crossTeam.getResponse().getStatus()).isEqualTo(404);
            assertSameError(crossTeam, absent);
            assertUnchanged(before);
        }

        @Test
        @DisplayName("AC-P6: 同チームの一般メンバー・SUPPORTER は403 COMMON_002で両枠とも不変")
        void AC_P6_一般メンバーとSUPPORTERは403で不変() throws Exception {
            Snapshot before = snapshot();
            for (Long actor : List.of(memberAId, supporterAId)) {
                setAuth(actor);
                MvcResult r = move(slotX, slotY, workerId);
                assertThat(r.getResponse().getStatus()).isEqualTo(403);
                assertThat((String) JsonPath.read(r.getResponse().getContentAsString(), "$.error.code"))
                        .isEqualTo("COMMON_002");
            }
            assertUnchanged(before);
        }

        @Test
        @DisplayName("AC-P6: 無所属ユーザーは不在と同一の404 SHIFT_002")
        void AC_P6_無所属は404() throws Exception {
            setAuth(outsiderId);
            MvcResult r = move(slotX, slotY, workerId);
            MvcResult absent = move(999_999_999L, slotY, workerId);
            assertThat(r.getResponse().getStatus()).isEqualTo(404);
            assertSameError(r, absent);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private record Snapshot(List<Long> x, long vx, List<Long> y, long vy, List<Long> full, long vFull) { }

    private Snapshot snapshot() {
        return new Snapshot(assignedOf(slotX), versionOf(slotX), assignedOf(slotY), versionOf(slotY),
                assignedOf(slotFull), versionOf(slotFull));
    }

    private void assertUnchanged(Snapshot before) {
        em.flush();
        em.clear();
        assertThat(snapshot()).as("両枠（と満員枠）の割当・version は不変").isEqualTo(before);
    }

    private MvcResult move(Long source, Long target, Long user) throws Exception {
        return mockMvc.perform(post(MOVE, source)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(moveBody(target, user, 0L, 0L))))
                .andReturn();
    }

    private int patchAssignments(Long slotId, List<Long> add, List<Long> remove, int version) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("addUserIds", add);
        body.put("removeUserIds", remove);
        body.put("slotVersion", version);
        MvcResult result = mockMvc.perform(patch("/api/v1/shifts/slots/{id}/assignments", slotId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.version");
    }

    private static Map<String, Object> moveBody(Long target, Long user, long sourceVersion, long targetVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetSlotId", target);
        body.put("userId", user);
        body.put("sourceSlotVersion", sourceVersion);
        body.put("targetSlotVersion", targetVersion);
        return body;
    }

    private static void assertSameError(MvcResult a, MvcResult b) throws Exception {
        String bodyA = a.getResponse().getContentAsString();
        String bodyB = b.getResponse().getContentAsString();
        assertThat(a.getResponse().getStatus()).isEqualTo(b.getResponse().getStatus());
        assertThat((String) JsonPath.read(bodyA, "$.error.code")).isEqualTo(JsonPath.read(bodyB, "$.error.code"));
        assertThat((String) JsonPath.read(bodyA, "$.error.message"))
                .isEqualTo(JsonPath.read(bodyB, "$.error.message"));
    }

    private Long versionOf(Long slotId) {
        return ((Number) em.createNativeQuery("SELECT version FROM shift_slots WHERE id = :id")
                .setParameter("id", slotId).getSingleResult()).longValue();
    }

    private List<Long> assignedOf(Long slotId) {
        em.flush();
        em.clear();
        ShiftSlotEntity slot = slotRepository.findById(slotId).orElseThrow();
        return ShiftAssignedUserIds.parse(slot.getAssignedUserIds());
    }

    private Long insertSchedule(Long team) {
        ShiftScheduleEntity schedule = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(team)
                .title("CMP1432 枠移動")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 11, 1))
                .endDate(LocalDate.of(2026, 11, 7))
                .status(ShiftScheduleStatus.ADJUSTING)
                .requestDeadline(LocalDateTime.of(2099, 1, 1, 0, 0))
                .createdBy(adminAId)
                .build());
        return schedule.getId();
    }

    private Long insertSlot(Long schedule, LocalTime start, LocalTime end, int required, List<Long> assigned) {
        return slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(schedule)
                .slotDate(LocalDate.of(2026, 11, 2))
                .startTime(start)
                .endTime(end)
                .requiredCount(required)
                .assignedUserIds(assigned.isEmpty() ? null : ShiftAssignedUserIds.serialize(assigned))
                .build()).getId();
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
                                + "VALUES (:email, 'CMP', 'テスト', 'CMP テスト', 'ACTIVE', "
                                + "1, 1, 1, 'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, "
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
