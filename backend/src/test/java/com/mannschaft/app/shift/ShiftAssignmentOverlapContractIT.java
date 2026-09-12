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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * シフト割当の重なり判定 契約テスト（試練 / red 先行）。
 *
 * <p>設計 F03.5 §11.3.5 の AC-3-07〜09 を固定する。金型は同ドメインの
 * {@code ShiftSlotScopeContractIT}（{@code PATCH /shifts/slots/{slotId}/assignments} を叩く既存 IT）。</p>
 *
 * <p><b>固定する契約</b>: 時間が重なる同一人物の割当は<b>機械的に禁止せず警告に留めて保存を許す</b>。
 * ただし完全一致（同一日・同一開始・同一終了）だけは 409 で拒否する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("シフト割当の重なり判定 契約テスト（AC-3群 IT）")
class ShiftAssignmentOverlapContractIT extends AbstractMySqlIntegrationTest {

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

    private static final LocalDate SLOT_DATE = LocalDate.of(2026, 3, 2);

    private Long teamId;
    private Long adminId;
    private Long workerId;
    private Long scheduleId;

    @BeforeEach
    void setUp() {
        teamId = insertTeam("重なり判定 チーム");
        adminId = insertUser("overlap-admin@example.com");
        workerId = insertUser("overlap-worker@example.com");

        // checkAdminOrAbove（user_roles）と isMember（memberships）は別系統のため ADMIN には両方張る。
        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
        MembershipTestHelper.insertMembership(em, workerId, ScopeType.TEAM, teamId, RoleKind.MEMBER);

        ShiftScheduleEntity schedule = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamId)
                .title("重なり判定テスト用スケジュール")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.PUBLISHED)
                .publishedAt(LocalDateTime.of(2026, 2, 20, 10, 0))
                .createdBy(adminId)
                .build());
        scheduleId = schedule.getId();

        em.flush();
        em.clear();
        setAuth(adminId);
    }

    @Test
    @DisplayName("AC-3-07 重なる割当は200＋警告ASSIGNMENT_OVERLAPで保存され相手のslotIdが返る")
    void AC_3_07_重なる割当は200で警告付き保存() throws Exception {
        Long baseSlotId = insertSlot(LocalTime.of(9, 0), LocalTime.of(12, 0));
        Long overlappingSlotId = insertSlot(LocalTime.of(11, 0), LocalTime.of(14, 0));

        assign(baseSlotId).andExpect(status().isOk());

        assign(overlappingSlotId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings[0].code").value("ASSIGNMENT_OVERLAP"))
                .andExpect(jsonPath("$.data.warnings[0].conflictingSlotIds")
                        .value(hasItem(baseSlotId.intValue())));
    }

    @Test
    @DisplayName("AC-3-08 完全一致（同一日・同一開始・同一終了・同一人物）の割当は409")
    void AC_3_08_完全一致の割当は409() throws Exception {
        Long baseSlotId = insertSlot(LocalTime.of(9, 0), LocalTime.of(12, 0));
        Long identicalSlotId = insertSlot(LocalTime.of(9, 0), LocalTime.of(12, 0));

        assign(baseSlotId).andExpect(status().isOk());

        assign(identicalSlotId).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("AC-3-09 同日の重ならない2本（12:00-15:00 と 16:00-20:00）への割当は警告が1件も出ない")
    void AC_3_09_重ならない2本は警告ゼロ() throws Exception {
        Long noonSlotId = insertSlot(LocalTime.of(12, 0), LocalTime.of(15, 0));
        Long eveningSlotId = insertSlot(LocalTime.of(16, 0), LocalTime.of(20, 0));

        assign(noonSlotId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings").isEmpty());

        assign(eveningSlotId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings").isEmpty());
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private ResultActions assign(Long slotId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("addUserIds", List.of(workerId));
        body.put("removeUserIds", List.of());
        body.put("slotVersion", 0);
        return mockMvc.perform(patch("/api/v1/shifts/slots/{id}/assignments", slotId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Long insertSlot(LocalTime startTime, LocalTime endTime) {
        ShiftSlotEntity slot = slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(scheduleId)
                .slotDate(SLOT_DATE)
                .startTime(startTime)
                .endTime(endTime)
                .endsNextDay(false)
                .requiredCount(1)
                .build());
        em.flush();
        return slot.getId();
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
                                + "VALUES (:email, '重なり', 'テスト', '重なり テスト', 'ACTIVE', "
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
