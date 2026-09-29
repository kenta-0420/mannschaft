package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftAssignmentEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftAssignmentRepository;
import com.mannschaft.app.shift.service.ShiftScheduleService;
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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 親スケジュール論理削除時の子（枠／希望）アクセス契約テスト（試練 / red 先行・CMP-260917-1136）。
 *
 * <p>CMP-260923-0953 では親削除を子3テーブルへ論理削除で連鎖させる。
 * 行・ID・業務値を保持し、通常 ORM と native の現在状態読取からは除外する。
 * 本人の提出履歴だけは削除済みも認可済みの経路で表示する。</p>
 *
 * <p><b>不具合(a)</b>: {@code ShiftSlotService#checkScheduleAdminAccess} / {@code checkScheduleReadAccess} は
 * SYSTEM_ADMIN を親の生存確認より先に短絡させていたため、<b>SYSTEM_ADMIN だけが親削除済みの枠を
 * 編集・削除できてしまい</b>、一般 ADMIN（{@code resolveTeamId} で404）と挙動が食い違っていた。
 * {@code ShiftRequestService.deleteRequest} も同型（本人一致なら親を一度も引かずに削除が通っていた）。</p>
 *
 * <p><b>案A</b>: 親の生存確認を SYSTEM_ADMIN にも一律で課す（本クラスの主眼）。</p>
 * <p><b>案C</b>: {@code GET /shifts/my/requests} は提出履歴を消さず、親削除済みなら
 * {@code scheduleDeleted=true} を返す（一覧からは消さない。詳細は404のまま）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("shift ドメイン（親削除済みスケジュールの子アクセス）契約テスト（試練・CMP-260917-1136）")
class ShiftSoftDeletedScheduleChildAccessContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShiftScheduleRepository scheduleRepository;

    @Autowired
    private ShiftSlotRepository slotRepository;

    @Autowired
    private ShiftRequestRepository requestRepository;

    @Autowired
    private ShiftAssignmentRepository assignmentRepository;

    @Autowired
    private ShiftScheduleService scheduleService;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;

    private Long systemAdminId;   // プラットフォーム級 SYSTEM_ADMIN（当該チームの非メンバー）
    private Long adminId;         // チームの一般 ADMIN
    private Long memberId;        // 希望の提出者本人（非ADMIN）

    private Long liveScheduleId;      // 生存しているスケジュール
    private Long liveSlotId;

    private Long deletedScheduleId;   // 論理削除済みスケジュール（孤児枠・孤児希望の親）
    private Long orphanSlotId;
    private Long orphanRequestId;

    @BeforeEach
    void setUp() {
        teamId = insertTeam("CMP-260917-1136 チーム");

        systemAdminId = insertUser("cmp260917-system-admin@example.com");
        adminId = insertUser("cmp260917-admin@example.com");
        memberId = insertUser("cmp260917-member@example.com");

        // SYSTEM_ADMIN はプラットフォーム級。当該チームの memberships は敢えて張らない
        // （ShiftUnpublishedScheduleVisibilityContractIT 踏襲）。
        MembershipTestHelper.insertUserRole(em, systemAdminId, "SYSTEM_ADMIN", null, null);

        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);

        // 生存しているスケジュール（退行確認用）
        ShiftScheduleEntity liveSchedule = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamId)
                .title("CMP-260917-1136 生存スケジュール")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 4, 1))
                .endDate(LocalDate.of(2026, 4, 7))
                .status(ShiftScheduleStatus.PUBLISHED)
                .publishedAt(LocalDateTime.of(2026, 3, 20, 10, 0))
                .createdBy(adminId)
                .build());
        liveScheduleId = liveSchedule.getId();

        ShiftSlotEntity liveSlot = slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(liveScheduleId)
                .slotDate(LocalDate.of(2026, 4, 2))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .requiredCount(2)
                .build());
        liveSlotId = liveSlot.getId();

        // 論理削除済みスケジュール（孤児の枠・希望の親）
        ShiftScheduleEntity deletedSchedule = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamId)
                .title("CMP-260917-1136 削除済みスケジュール")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.PUBLISHED)
                .publishedAt(LocalDateTime.of(2026, 2, 20, 10, 0))
                .createdBy(adminId)
                .build());
        deletedScheduleId = deletedSchedule.getId();

        ShiftSlotEntity orphanSlot = slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(deletedScheduleId)
                .slotDate(LocalDate.of(2026, 3, 2))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .requiredCount(2)
                .build());
        orphanSlotId = orphanSlot.getId();

        ShiftRequestEntity orphanRequest = requestRepository.save(ShiftRequestEntity.builder()
                .scheduleId(deletedScheduleId)
                .userId(memberId)
                .slotDate(LocalDate.of(2026, 3, 2))
                .preference(ShiftPreference.PREFERRED)
                .build());
        orphanRequestId = orphanRequest.getId();

        // 親を論理削除する（@SQLRestriction により以降 findById 等は見えなくなる）。
        scheduleService.deleteSchedule(deletedScheduleId, adminId);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 不具合(a) 案A: 親削除済みの枠に対する SYSTEM_ADMIN 更新
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PATCH /shifts/slots/{slotId}（親削除済み・SYSTEM_ADMIN）")
    class UpdateOrphanSlotAsSystemAdmin {

        @Test
        @DisplayName("SYSTEM_ADMINでも親削除済みなら404（案A本体）")
        void SYSTEM_ADMINでも親削除済みなら404() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(patch("/api/v1/shifts/slots/{id}", orphanSlotId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("note", "亡霊枠編集"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("SYSTEM_ADMINは親が生存していれば200（退行なし）")
        void SYSTEM_ADMINは親が生存していれば200() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(patch("/api/v1/shifts/slots/{id}", liveSlotId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("note", "生存枠編集"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("一般ADMINは親削除済みなら404（退行なし・既存挙動）")
        void 一般ADMINは親削除済みなら404() throws Exception {
            setAuth(adminId);
            mockMvc.perform(patch("/api/v1/shifts/slots/{id}", orphanSlotId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("note", "亡霊枠編集"))))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("DELETE /shifts/slots/{slotId}（親削除済み・SYSTEM_ADMIN）")
    class DeleteOrphanSlotAsSystemAdmin {

        @Test
        @DisplayName("SYSTEM_ADMINでも親削除済みなら404（案A本体）")
        void SYSTEM_ADMINでも親削除済みなら404() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(delete("/api/v1/shifts/slots/{id}", orphanSlotId))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 不具合(b): 希望削除は親が削除済みなら本人でも拒否
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DELETE /shifts/requests/{requestId}（親削除済み・本人）")
    class DeleteOrphanRequestAsOwner {

        @Test
        @DisplayName("本人でも親削除済みの希望を削除しようとすると404")
        void 本人でも親削除済みなら404() throws Exception {
            setAuth(memberId);
            mockMvc.perform(delete("/api/v1/shifts/requests/{id}", orphanRequestId))
                    .andExpect(status().isNotFound());

            // 対処療法（例外を握りつぶして何もしない）でないことも実データで裏取りする:
            // 行は消えていないはず（404 は認可・存在確認の結果であり、削除処理自体は走っていない）。
            assertThat(em.createNativeQuery("SELECT COUNT(*) FROM shift_requests WHERE id = :id")
                    .setParameter("id", orphanRequestId).getSingleResult()).isEqualTo(1L);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 案C: GET /shifts/my/requests は削除済みでも一覧に残し、フラグを立てる
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /shifts/my/requests（案C）")
    class ListMyRequests {

        @Test
        @DisplayName("親削除済みの希望も一覧からは消えず、scheduleDeleted=trueが立つ")
        void 親削除済みの希望はscheduleDeletedがtrue() throws Exception {
            setAuth(memberId);
            mockMvc.perform(get("/api/v1/shifts/my/requests"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(orphanRequestId))
                    .andExpect(jsonPath("$.data[0].scheduleDeleted").value(true));
        }

        @Test
        @DisplayName("本人が取り下げた希望は同じ秒に親削除されても履歴へ出ない")
        void 本人取り下げは親削除履歴へ混入しない() throws Exception {
            ShiftRequestEntity withdrawn = requestRepository.save(ShiftRequestEntity.builder()
                    .scheduleId(liveScheduleId).userId(memberId).slotId(liveSlotId)
                    .slotDate(LocalDate.of(2026, 4, 2)).preference(ShiftPreference.AVAILABLE).build());
            em.flush();
            Long withdrawnId = withdrawn.getId();
            setAuth(memberId);

            mockMvc.perform(delete("/api/v1/shifts/requests/{id}", withdrawnId))
                    .andExpect(status().isNoContent());
            setAuth(adminId);
            mockMvc.perform(delete("/api/v1/shifts/schedules/{id}", liveScheduleId))
                    .andExpect(status().isNoContent());
            em.flush();
            em.clear();

            assertThat(em.createNativeQuery("SELECT delete_reason FROM shift_requests WHERE id = :id")
                    .setParameter("id", withdrawnId).getSingleResult()).isEqualTo("WITHDRAWN");
            setAuth(memberId);
            mockMvc.perform(get("/api/v1/shifts/my/requests"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.id == " + withdrawnId + ")]").isEmpty());
        }
    }

    @Test
    @DisplayName("枠DELETEは行を残し割当と枠指定希望だけを論理削除する")
    void 枠単体削除は対象の子だけへ連鎖する() throws Exception {
        ShiftRequestEntity slotRequest = requestRepository.save(ShiftRequestEntity.builder()
                .scheduleId(liveScheduleId).userId(memberId).slotId(liveSlotId)
                .slotDate(LocalDate.of(2026, 4, 2)).preference(ShiftPreference.PREFERRED).build());
        ShiftRequestEntity dayRequest = requestRepository.save(ShiftRequestEntity.builder()
                .scheduleId(liveScheduleId).userId(memberId).slotDate(LocalDate.of(2026, 4, 2))
                .preference(ShiftPreference.AVAILABLE).build());
        ShiftAssignmentEntity assignment = assignmentRepository.save(ShiftAssignmentEntity.builder()
                .slotId(liveSlotId).userId(memberId).assignedBy(adminId)
                .status(ShiftAssignmentStatus.CONFIRMED).build());
        em.flush();
        Long slotRequestId = slotRequest.getId();
        Long dayRequestId = dayRequest.getId();
        Long assignmentId = assignment.getId();
        em.clear();
        setAuth(adminId);

        mockMvc.perform(delete("/api/v1/shifts/slots/{id}", liveSlotId))
                .andExpect(status().isNoContent());
        em.flush();
        em.clear();

        assertThat(slotRepository.findById(liveSlotId)).isEmpty();
        assertThat(em.createNativeQuery("SELECT COUNT(*) FROM shift_slots WHERE id = :id")
                .setParameter("id", liveSlotId).getSingleResult()).isEqualTo(1L);
        assertThat(em.createNativeQuery("SELECT delete_reason FROM shift_requests WHERE id = :id")
                .setParameter("id", slotRequestId).getSingleResult()).isEqualTo("SLOT_DELETED");
        assertThat(em.createNativeQuery("SELECT deleted_at FROM shift_assignments WHERE id = :id")
                .setParameter("id", assignmentId).getSingleResult()).isNotNull();
        assertThat(em.createNativeQuery("SELECT deleted_at FROM shift_requests WHERE id = :id")
                .setParameter("id", dayRequestId).getSingleResult()).isNull();
    }

    @Test
    @DisplayName("希望取り下げ後は同じ枠へ再提出でき有効行は1件だけになる")
    void 希望取り下げ後に再提出できる() throws Exception {
        ShiftRequestEntity first = requestRepository.save(ShiftRequestEntity.builder()
                .scheduleId(liveScheduleId).userId(memberId).slotId(liveSlotId)
                .slotDate(LocalDate.of(2026, 4, 2)).preference(ShiftPreference.PREFERRED).build());
        em.flush();
        Long firstId = first.getId();
        em.clear();
        setAuth(memberId);

        mockMvc.perform(delete("/api/v1/shifts/requests/{id}", firstId))
                .andExpect(status().isNoContent());
        ShiftRequestEntity second = requestRepository.saveAndFlush(ShiftRequestEntity.builder()
                .scheduleId(liveScheduleId).userId(memberId).slotId(liveSlotId)
                .slotDate(LocalDate.of(2026, 4, 2)).preference(ShiftPreference.AVAILABLE).build());
        em.clear();

        assertThat(second.getId()).isNotEqualTo(firstId);
        assertThat(em.createNativeQuery("""
                SELECT COUNT(*) FROM shift_requests
                WHERE schedule_id = :scheduleId AND user_id = :userId AND slot_id = :slotId
                """).setParameter("scheduleId", liveScheduleId).setParameter("userId", memberId)
                .setParameter("slotId", liveSlotId).getSingleResult()).isEqualTo(2L);
        assertThat(em.createNativeQuery("""
                SELECT COUNT(*) FROM shift_requests
                WHERE schedule_id = :scheduleId AND user_id = :userId AND slot_id = :slotId
                  AND deleted_at IS NULL
                """).setParameter("scheduleId", liveScheduleId).setParameter("userId", memberId)
                .setParameter("slotId", liveSlotId).getSingleResult()).isEqualTo(1L);
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("親DELETEは枠・希望・割当を同じ削除日時で残し通常読取から隠す（CMP-260923-0953）")
    void 親削除が全子へ論理削除を連鎖する() throws Exception {
        slotRepository.findById(liveSlotId).orElseThrow()
                .updateAssignedUserIds("[" + memberId + "]");
        ShiftRequestEntity request = requestRepository.save(ShiftRequestEntity.builder()
                .scheduleId(liveScheduleId).userId(memberId).slotId(liveSlotId)
                .slotDate(LocalDate.of(2026, 4, 2)).preference(ShiftPreference.PREFERRED)
                .note("保持する希望").build());
        ShiftAssignmentEntity assignment = assignmentRepository.save(ShiftAssignmentEntity.builder()
                .slotId(liveSlotId).userId(memberId).assignedBy(adminId)
                .status(ShiftAssignmentStatus.CONFIRMED).note("保持する割当").build());
        Long requestId = request.getId();
        Long assignmentId = assignment.getId();
        em.flush();
        em.clear();

        setAuth(adminId);
        mockMvc.perform(delete("/api/v1/shifts/schedules/{id}", liveScheduleId))
                .andExpect(status().isNoContent());
        em.flush();
        em.clear();

        Object deletedAt = em.createNativeQuery("SELECT deleted_at FROM shift_schedules WHERE id = :id")
                .setParameter("id", liveScheduleId).getSingleResult();
        assertThat(deletedAt).isNotNull();
        for (Map.Entry<String, Long> row : Map.of("shift_slots", liveSlotId,
                "shift_requests", requestId, "shift_assignments", assignmentId).entrySet()) {
            assertThat(em.createNativeQuery("SELECT deleted_at FROM " + row.getKey() + " WHERE id = :id")
                    .setParameter("id", row.getValue()).getSingleResult()).isEqualTo(deletedAt);
        }
        assertThat(slotRepository.findById(liveSlotId)).isEmpty();
        assertThat(requestRepository.findById(requestId)).isEmpty();
        assertThat(assignmentRepository.findById(assignmentId)).isEmpty();
        assertThat(em.createNativeQuery("SELECT assigned_user_ids FROM shift_slots WHERE id = :id")
                .setParameter("id", liveSlotId).getSingleResult()).isEqualTo("[" + memberId + "]");
        assertThat(em.createNativeQuery("SELECT status FROM shift_assignments WHERE id = :id")
                .setParameter("id", assignmentId).getSingleResult()).isEqualTo("CONFIRMED");
        assertThat(em.createNativeQuery("SELECT note FROM shift_requests WHERE id = :id")
                .setParameter("id", requestId).getSingleResult()).isEqualTo("保持する希望");
        assertThat(em.createNativeQuery("SELECT note FROM shift_assignments WHERE id = :id")
                .setParameter("id", assignmentId).getSingleResult()).isEqualTo("保持する割当");
        setAuth(memberId);
        mockMvc.perform(get("/api/v1/shifts/my/requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(requestId))
                .andExpect(jsonPath("$.data[0].scheduleDeleted").value(true));
        setAuth(systemAdminId);
        mockMvc.perform(get("/api/v1/shifts/my/requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void 子がゼロの親も削除でき他の親の子を変更しない() throws Exception {
        Long otherTeamId = insertTeam("CMP-260923-0953 別チーム");
        ShiftScheduleEntity otherParent = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(otherTeamId).title("別チームの保持親").periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 4, 1)).endDate(LocalDate.of(2026, 4, 7))
                .status(ShiftScheduleStatus.DRAFT).createdBy(adminId).build());
        ShiftSlotEntity otherSlot = slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(otherParent.getId()).slotDate(LocalDate.of(2026, 4, 2))
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0)).build());
        ShiftRequestEntity otherRequest = requestRepository.save(ShiftRequestEntity.builder()
                .scheduleId(otherParent.getId()).userId(memberId).slotDate(LocalDate.of(2026, 4, 2))
                .preference(ShiftPreference.AVAILABLE).build());
        ShiftAssignmentEntity otherAssignment = assignmentRepository.save(ShiftAssignmentEntity.builder()
                .slotId(otherSlot.getId()).userId(memberId).assignedBy(adminId).build());
        Long otherSlotId = otherSlot.getId();
        Long otherRequestId = otherRequest.getId();
        Long otherAssignmentId = otherAssignment.getId();
        ShiftScheduleEntity empty = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamId).title("子なし").periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 4, 1)).endDate(LocalDate.of(2026, 4, 7))
                .status(ShiftScheduleStatus.DRAFT).createdBy(adminId).build());
        em.flush();
        Long emptyId = empty.getId();
        em.clear();
        setAuth(adminId);

        mockMvc.perform(delete("/api/v1/shifts/schedules/{id}", emptyId))
                .andExpect(status().isNoContent());
        em.flush();
        em.clear();

        assertThat(scheduleRepository.findById(emptyId)).isEmpty();
        assertThat(slotRepository.findById(liveSlotId)).isPresent();
        assertThat(slotRepository.findById(otherSlotId)).isPresent();
        assertThat(requestRepository.findById(otherRequestId)).isPresent();
        assertThat(assignmentRepository.findById(otherAssignmentId)).isPresent();
        assertThat(em.createNativeQuery("SELECT deleted_at FROM shift_slots WHERE id = :id")
                .setParameter("id", liveSlotId).getSingleResult()).isNull();
    }

    @Test
    void 複数の子を削除し既設定の削除日時と再削除時の日時を保持する() throws Exception {
        ShiftSlotEntity secondSlot = slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(liveScheduleId).slotDate(LocalDate.of(2026, 4, 3))
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0)).build());
        Long secondId = secondSlot.getId();
        em.flush();
        em.createNativeQuery("UPDATE shift_slots SET deleted_at = '2020-01-01 00:00:00' WHERE id = :id")
                .setParameter("id", secondId).executeUpdate();
        // 既に削除済みの枠にも履歴が残り得るため、枠の通常フィルタにcascade対象を依存させない。
        ShiftAssignmentEntity assignment = assignmentRepository.save(ShiftAssignmentEntity.builder()
                .slotId(secondId).userId(memberId).assignedBy(adminId)
                .status(ShiftAssignmentStatus.CONFIRMED).build());
        Long assignmentId = assignment.getId();
        ShiftAssignmentEntity retainedAssignment = assignmentRepository.save(ShiftAssignmentEntity.builder()
                .slotId(liveSlotId).userId(memberId).assignedBy(adminId)
                .status(ShiftAssignmentStatus.CONFIRMED).build());
        ShiftRequestEntity retainedRequest = requestRepository.save(ShiftRequestEntity.builder()
                .scheduleId(liveScheduleId).userId(memberId).slotDate(LocalDate.of(2026, 4, 3))
                .preference(ShiftPreference.AVAILABLE).build());
        Map<String, Long> retainedRows = Map.of("shift_slots", secondId,
                "shift_requests", retainedRequest.getId(), "shift_assignments", retainedAssignment.getId());
        em.flush();
        em.createNativeQuery("UPDATE shift_assignments SET deleted_at = '2020-01-01 00:00:00' WHERE id = :id")
                .setParameter("id", retainedAssignment.getId()).executeUpdate();
        em.createNativeQuery("""
                UPDATE shift_requests SET deleted_at = '2020-01-01 00:00:00', delete_reason = 'WITHDRAWN'
                WHERE id = :id
                """).setParameter("id", retainedRequest.getId()).executeUpdate();
        em.clear();
        Map<String, Object> retainedTimestamps = new LinkedHashMap<>();
        for (Map.Entry<String, Long> row : retainedRows.entrySet()) {
            retainedTimestamps.put(row.getKey(), em.createNativeQuery(
                            "SELECT deleted_at FROM " + row.getKey() + " WHERE id = :id")
                    .setParameter("id", row.getValue()).getSingleResult());
        }
        setAuth(adminId);

        mockMvc.perform(delete("/api/v1/shifts/schedules/{id}", liveScheduleId))
                .andExpect(status().isNoContent());
        em.flush();
        em.clear();

        Object parentDeletedAt = em.createNativeQuery("SELECT deleted_at FROM shift_schedules WHERE id = :id")
                .setParameter("id", liveScheduleId).getSingleResult();
        for (Map.Entry<String, Long> row : retainedRows.entrySet()) {
            assertThat(em.createNativeQuery("SELECT deleted_at FROM " + row.getKey() + " WHERE id = :id")
                    .setParameter("id", row.getValue()).getSingleResult())
                    .as("既設定の削除日時を保持する: %s", row.getKey())
                    .isEqualTo(retainedTimestamps.get(row.getKey()));
        }
        assertThat(em.createNativeQuery("SELECT deleted_at FROM shift_assignments WHERE id = :id")
                .setParameter("id", assignmentId).getSingleResult()).isEqualTo(parentDeletedAt);
        mockMvc.perform(delete("/api/v1/shifts/schedules/{id}", liveScheduleId))
                .andExpect(status().isNotFound());
        assertThat(em.createNativeQuery("SELECT deleted_at FROM shift_schedules WHERE id = :id")
                .setParameter("id", liveScheduleId).getSingleResult()).isEqualTo(parentDeletedAt);
    }

    @Test
    void 生存親でも削除済み枠はネイティブ読取から除外する() {
        ShiftSlotEntity hidden = slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(liveScheduleId).slotDate(LocalDate.of(2026, 4, 4))
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0))
                .assignedUserIds("[" + memberId + "]").build());
        Long hiddenId = hidden.getId();
        em.flush();
        em.createNativeQuery("UPDATE shift_slots SET deleted_at = '2026-03-01 00:00:00' WHERE id = :id")
                .setParameter("id", hiddenId).executeUpdate();
        em.clear();

        assertThat(slotRepository.findById(hiddenId)).isEmpty();
        assertThat(slotRepository.findAllAssignedToUser(memberId)).isEmpty();
        assertThat(slotRepository.findUpcomingAssignedByUserIdBetween(
                memberId, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 8))).isEmpty();
        assertThat(em.createNativeQuery("SELECT assigned_user_ids FROM shift_slots WHERE id = :id")
                .setParameter("id", hiddenId).getSingleResult()).isEqualTo("[" + memberId + "]");
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
                                + "VALUES (:email, 'CMP260917', 'テスト', 'CMP260917 テスト', 'ACTIVE', "
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
