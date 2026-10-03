package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.dto.CreateShiftSlotRequest;
import com.mannschaft.app.shift.entity.ShiftAssignmentEntity;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftAssignmentRepository;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.service.ShiftCleanupBatchService;
import com.mannschaft.app.shift.service.ShiftScheduleService;
import com.mannschaft.app.shift.service.ShiftSlotService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
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
 * <p><b>不具合(a)</b>: 旧 {@code ShiftSlotService} の認可（現 {@code ShiftSlotFacade}。CMP-260923-0954 W6a でトランザクションの外へ移した）は
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

    @MockitoSpyBean
    private ShiftScheduleRepository scheduleRepository;

    @MockitoSpyBean
    private ShiftSlotRepository slotRepository;

    @MockitoSpyBean
    private ShiftRequestRepository requestRepository;

    @MockitoSpyBean
    private ShiftAssignmentRepository assignmentRepository;

    @Autowired
    private ShiftScheduleService scheduleService;

    @Autowired
    private ShiftSlotService slotService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ShiftCleanupBatchService cleanupBatchService;

    @Autowired
    private FeatureFlagRepository featureFlagRepository;

    @Autowired
    private CacheManager cacheManager;

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
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, "FEATURE_SHIFT_ENABLED");
        String fixtureSuffix = Long.toUnsignedString(System.nanoTime(), Character.MAX_RADIX);
        teamId = insertTeam("CMP-260917-1136 チーム-" + fixtureSuffix);

        systemAdminId = insertUser("cmp260917-system-admin-" + fixtureSuffix + "@example.com");
        adminId = insertUser("cmp260917-admin-" + fixtureSuffix + "@example.com");
        memberId = insertUser("cmp260917-member-" + fixtureSuffix + "@example.com");

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
    @DisplayName("枠一括更新が失敗すると親と子3種を実DBでロールバックする")
    void 枠連鎖失敗時は親子を全てロールバックする() {
        ShiftRequestEntity request = requestRepository.save(ShiftRequestEntity.builder()
                .scheduleId(liveScheduleId).userId(memberId).slotId(liveSlotId)
                .slotDate(LocalDate.of(2026, 4, 2)).preference(ShiftPreference.PREFERRED).build());
        ShiftAssignmentEntity assignment = assignmentRepository.save(ShiftAssignmentEntity.builder()
                .slotId(liveSlotId).userId(memberId).assignedBy(adminId).build());
        Long requestId = request.getId();
        Long assignmentId = assignment.getId();
        em.flush();
        TestTransaction.flagForCommit();
        TestTransaction.end();

        willThrow(new RuntimeException("AC-3 枠更新失敗"))
                .given(slotRepository).softDeleteByScheduleId(liveScheduleId);
        TestTransaction.start();
        assertThatThrownBy(() -> scheduleService.deleteSchedule(liveScheduleId, adminId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("AC-3 枠更新失敗");
        TestTransaction.end();

        TestTransaction.start();
        assertThat(em.createNativeQuery("SELECT deleted_at FROM shift_schedules WHERE id = :id")
                .setParameter("id", liveScheduleId).getSingleResult()).isNull();
        assertThat(em.createNativeQuery("SELECT deleted_at FROM shift_slots WHERE id = :id")
                .setParameter("id", liveSlotId).getSingleResult()).isNull();
        assertThat(em.createNativeQuery("SELECT deleted_at FROM shift_requests WHERE id = :id")
                .setParameter("id", requestId).getSingleResult()).isNull();
        assertThat(em.createNativeQuery("SELECT deleted_at FROM shift_assignments WHERE id = :id")
                .setParameter("id", assignmentId).getSingleResult()).isNull();
    }

    @Test
    @DisplayName("親削除と枠作成が競合しても削除完了後に有効な枠を残さない")
    void 親削除と枠作成を親行ロックで直列化する() throws Exception {
        TestTransaction.flagForCommit();
        TestTransaction.end();
        CountDownLatch parentLocked = new CountDownLatch(1);
        CountDownLatch createAttempted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> deletion = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                scheduleRepository.findByIdForUpdate(liveScheduleId).orElseThrow();
                parentLocked.countDown();
                try {
                    if (!createAttempted.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("枠作成スレッドが開始しない");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                scheduleService.deleteSchedule(liveScheduleId, adminId);
            }));
            Future<?> creation = executor.submit(() -> {
                assertThat(parentLocked.await(5, TimeUnit.SECONDS)).isTrue();
                createAttempted.countDown();
                try {
                    return new TransactionTemplate(transactionManager).execute(tx ->
                            slotService.createSlot(liveScheduleId,
                                    new CreateShiftSlotRequest(LocalDate.of(2026, 4, 3),
                                            LocalTime.of(10, 0), LocalTime.of(12, 0), null, 1, "競合作成")));
                } catch (BusinessException | org.springframework.transaction.UnexpectedRollbackException expected) {
                    return null;
                }
            });
            deletion.get(10, TimeUnit.SECONDS);
            creation.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        TestTransaction.start();
        assertThat(em.createNativeQuery("""
                SELECT COUNT(*) FROM shift_slots WHERE schedule_id = :scheduleId AND deleted_at IS NULL
                """).setParameter("scheduleId", liveScheduleId).getSingleResult()).isEqualTo(0L);
    }

    @Test
    @DisplayName("希望が残るARCHIVED親101件を複数回の実行で全て物理削除する")
    void cleanupは百一件を複数回で完掃する() {
        for (int i = 0; i < 101; i++) {
            ShiftScheduleEntity schedule = scheduleRepository.save(ShiftScheduleEntity.builder()
                    .teamId(teamId).title("cleanup-" + i).periodType(ShiftPeriodType.WEEKLY)
                    .startDate(LocalDate.of(2025, 1, 1)).endDate(LocalDate.of(2025, 1, 7))
                    .status(ShiftScheduleStatus.ARCHIVED).createdBy(adminId).build());
            requestRepository.save(ShiftRequestEntity.builder().scheduleId(schedule.getId()).userId(memberId)
                    .slotDate(LocalDate.of(2025, 1, 2)).preference(ShiftPreference.AVAILABLE).build());
        }
        em.flush();
        em.createNativeQuery("UPDATE shift_schedules SET updated_at = '2025-01-01 00:00:00' "
                + "WHERE title LIKE 'cleanup-%'").executeUpdate();
        em.clear();

        cleanupBatchService.runRequestCleanup();
        cleanupBatchService.runRequestCleanup();

        assertThat(em.createNativeQuery("""
                SELECT COUNT(*) FROM shift_requests r JOIN shift_schedules s ON s.id = r.schedule_id
                WHERE s.title LIKE 'cleanup-%'
                """).getSingleResult()).isEqualTo(0L);
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
    @DisplayName("AC-9a〜l 生存親配下の削除済み枠・希望は全読取材料から除外する")
    void 生存親でも削除済み枠はネイティブ読取から除外する() {
        ShiftSlotEntity hidden = slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(liveScheduleId).slotDate(LocalDate.of(2026, 4, 4))
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0))
                .assignedUserIds("[" + memberId + "]").build());
        ShiftRequestEntity hiddenRequest = requestRepository.save(ShiftRequestEntity.builder()
                .scheduleId(liveScheduleId).userId(memberId).slotId(hidden.getId())
                .slotDate(LocalDate.of(2026, 4, 4)).preference(ShiftPreference.PREFERRED).build());
        Long hiddenId = hidden.getId();
        Long hiddenRequestId = hiddenRequest.getId();
        em.flush();
        em.createNativeQuery("UPDATE shift_slots SET deleted_at = '2026-03-01 00:00:00' WHERE id = :id")
                .setParameter("id", hiddenId).executeUpdate();
        em.createNativeQuery("""
                UPDATE shift_requests
                SET deleted_at = '2026-03-01 00:00:00', delete_reason = 'SLOT_DELETED'
                WHERE id = :id
                """).setParameter("id", hiddenRequestId).executeUpdate();
        em.clear();

        assertThat(slotRepository.findById(hiddenId)).isEmpty();
        assertThat(slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(liveScheduleId))
                .extracting(ShiftSlotEntity::getId).doesNotContain(hiddenId);
        assertThat(slotRepository.findByScheduleIdAndSlotDateOrderByStartTimeAsc(
                liveScheduleId, LocalDate.of(2026, 4, 4))).isEmpty();
        assertThat(slotRepository.findAllByIdIn(List.of(hiddenId))).isEmpty();
        assertThat(slotRepository.countByScheduleId(liveScheduleId)).isEqualTo(1L);
        assertThat(slotRepository.findAllAssignedToUser(memberId)).isEmpty();
        assertThat(slotRepository.findUpcomingAssignedByUserIdBetween(
                memberId, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 8))).isEmpty();
        assertThat(requestRepository.findById(hiddenRequestId)).isEmpty();
        assertThat(requestRepository.findByScheduleIdOrderBySlotDateAsc(liveScheduleId)).isEmpty();
        assertThat(requestRepository.findByScheduleIdAndUserId(liveScheduleId, memberId)).isEmpty();
        assertThat(requestRepository.findByScheduleIdAndSlotDate(
                liveScheduleId, LocalDate.of(2026, 4, 4))).isEmpty();
        assertThat(requestRepository.findByScheduleIdAndUserIdAndSlotId(
                liveScheduleId, memberId, hiddenId)).isEmpty();
        assertThat(requestRepository.countDistinctUserIdByScheduleId(liveScheduleId)).isZero();
        assertThat(requestRepository.countSubmittedMembersByScheduleId(
                liveScheduleId, List.of(memberId))).isZero();
        assertThat(requestRepository.findByUserIdOrderBySlotDateDesc(memberId)).isEmpty();
        assertThat(requestRepository.countByScheduleIdAndPreference(
                liveScheduleId, ShiftPreference.PREFERRED)).isZero();
        assertThat(requestRepository.countByPreferenceForSchedule(liveScheduleId)).isEmpty();
        assertThat(em.createNativeQuery("SELECT assigned_user_ids FROM shift_slots WHERE id = :id")
                .setParameter("id", hiddenId).getSingleResult()).isEqualTo("[" + memberId + "]");
    }

    @Test
    @DisplayName("子1件と200件で親削除のSQL数は同じで更新は4文、連鎖更新は索引を使う")
    void 親削除は子件数に比例せず四更新文と索引で実行する() {
        Long oneChildSchedule = createScheduleWithChildren("AC-15-one", 1);
        Long twoHundredChildSchedule = createScheduleWithChildren("AC-15-two-hundred", 200);
        em.flush();
        em.clear();

        Statistics statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        scheduleService.deleteSchedule(oneChildSchedule, systemAdminId);
        em.flush();
        long oneChildStatements = statistics.getPrepareStatementCount();

        em.clear();
        statistics.clear();
        clearInvocations(scheduleRepository, assignmentRepository, requestRepository, slotRepository);
        scheduleService.deleteSchedule(twoHundredChildSchedule, systemAdminId);
        em.flush();
        long twoHundredChildStatements = statistics.getPrepareStatementCount();

        assertThat(twoHundredChildStatements)
                .as("子1件=%d文、子200件=%d文", oneChildStatements, twoHundredChildStatements)
                .isEqualTo(oneChildStatements);
        assertThat(statistics.getEntityUpdateCount()).as("親のORM UPDATEは1文").isEqualTo(1L);
        verify(scheduleRepository).saveAndFlush(org.mockito.ArgumentMatchers.any(ShiftScheduleEntity.class));
        verify(assignmentRepository).softDeleteByScheduleId(twoHundredChildSchedule);
        verify(requestRepository).softDeleteByScheduleId(twoHundredChildSchedule);
        verify(slotRepository).softDeleteByScheduleId(twoHundredChildSchedule);

        Map<String, String> slotPlan = explainKeys("""
                EXPLAIN UPDATE shift_slots s FORCE INDEX (idx_sslot_schedule_date)
                SET s.deleted_at = (SELECT sc.deleted_at FROM shift_schedules sc WHERE sc.id = :scheduleId),
                    s.version = s.version + 1, s.updated_at = s.updated_at
                WHERE s.schedule_id = :scheduleId AND s.deleted_at IS NULL
                """, twoHundredChildSchedule);
        Map<String, String> assignmentPlan = explainKeys("""
                EXPLAIN UPDATE shift_slots s FORCE INDEX (idx_sslot_schedule_date)
                STRAIGHT_JOIN shift_assignments a FORCE INDEX (idx_shift_assignments_slot_id) ON a.slot_id = s.id
                SET a.deleted_at = (SELECT sc.deleted_at FROM shift_schedules sc WHERE sc.id = :scheduleId),
                    a.version = a.version + 1, a.updated_at = a.updated_at
                WHERE s.schedule_id = :scheduleId AND a.deleted_at IS NULL
                """, twoHundredChildSchedule);
        assertThat(slotPlan.get("s")).isEqualTo("idx_sslot_schedule_date");
        assertThat(assignmentPlan.get("a")).isEqualTo("idx_shift_assignments_slot_id");
    }

    @Test
    @DisplayName("親削除後の古い枠・希望・割当をsaveAndFlushしても蘇生しない")
    void 古いdetached子実体の保存でdeletedAtはnullへ戻らない() {
        ShiftRequestEntity request = requestRepository.save(ShiftRequestEntity.builder()
                .scheduleId(liveScheduleId).userId(memberId).slotId(liveSlotId)
                .slotDate(LocalDate.of(2026, 4, 2)).preference(ShiftPreference.PREFERRED).build());
        ShiftAssignmentEntity assignment = assignmentRepository.save(ShiftAssignmentEntity.builder()
                .slotId(liveSlotId).userId(memberId).assignedBy(adminId).build());
        ShiftSlotEntity staleSlot = slotRepository.findById(liveSlotId).orElseThrow();
        ShiftRequestEntity staleRequest = requestRepository.findById(request.getId()).orElseThrow();
        ShiftAssignmentEntity staleAssignment = assignmentRepository.findById(assignment.getId()).orElseThrow();
        Long requestId = staleRequest.getId();
        Long assignmentId = staleAssignment.getId();
        em.flush();
        TestTransaction.flagForCommit();
        TestTransaction.end();

        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> scheduleService.deleteSchedule(liveScheduleId, adminId));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> slotRepository.saveAndFlush(staleSlot)))
                .isInstanceOfAny(ObjectOptimisticLockingFailureException.class, OptimisticLockException.class);
        tryStaleSave(() -> requestRepository.saveAndFlush(staleRequest));
        tryStaleSave(() -> assignmentRepository.saveAndFlush(staleAssignment));

        TestTransaction.start();
        for (Map.Entry<String, Long> row : Map.of(
                "shift_slots", liveSlotId,
                "shift_requests", requestId,
                "shift_assignments", assignmentId).entrySet()) {
            assertThat(em.createNativeQuery("SELECT deleted_at FROM " + row.getKey() + " WHERE id = :id")
                    .setParameter("id", row.getValue()).getSingleResult())
                    .as("stale save後も削除日時を保持する: %s", row.getKey()).isNotNull();
        }
    }

    private Long createScheduleWithChildren(String title, int childCount) {
        ShiftScheduleEntity schedule = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamId).title(title).periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 6, 1)).endDate(LocalDate.of(2026, 12, 31))
                .status(ShiftScheduleStatus.DRAFT).createdBy(adminId).build());
        for (int i = 0; i < childCount; i++) {
            LocalDate date = LocalDate.of(2026, 6, 1).plusDays(i);
            ShiftSlotEntity slot = slotRepository.save(ShiftSlotEntity.builder()
                    .scheduleId(schedule.getId()).slotDate(date)
                    .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0)).build());
            requestRepository.save(ShiftRequestEntity.builder()
                    .scheduleId(schedule.getId()).userId(memberId).slotId(slot.getId())
                    .slotDate(date).preference(ShiftPreference.AVAILABLE).build());
            assignmentRepository.save(ShiftAssignmentEntity.builder()
                    .slotId(slot.getId()).userId(memberId).assignedBy(adminId).build());
        }
        return schedule.getId();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> explainKeys(String sql, Long scheduleId) {
        Map<String, String> keys = new java.util.HashMap<>();
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("scheduleId", scheduleId)
                .getResultList();
        for (Object[] row : rows) {
            keys.put(String.valueOf(row[2]), row[6] == null ? null : String.valueOf(row[6]));
        }
        return keys;
    }

    private void tryStaleSave(Runnable save) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> save.run());
        } catch (RuntimeException expected) {
            // @SQLRestriction 配下の merge は楽観ロックまたは既存PKへの再INSERTで失敗してよい。
            // 契約は、どちらの場合でも deleted_at が NULL に戻らないことである。
        }
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
