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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261010-1432 P — 枠間移動の重複判定が、親ロック取得後の「現在読み取り」で行われることの並行契約テスト。
 *
 * <p>是正前の欠陥: {@code moveAssignment} 冒頭の通常読み取りで MySQL REPEATABLE READ のスナップショットが
 * 親ロック取得前に確定する。別操作が親ロックを保持したまま、移動先と完全一致の時間帯の第三の枠へ
 * 当該ユーザーを追加してコミットしても、移動処理はロック待機後に古い第三の枠を読み、
 * 本来 {@code SHIFT_042}（409）で拒否すべき完全一致の二重割当を保存していた。</p>
 *
 * <p><b>待機順序の固定</b>: 操作 A（別スレッド）が親ロックを取って保持 → 移動処理を開始 →
 * {@link ShiftScheduleRepository#findByIdForUpdate} の spy で「移動処理が親ロック待ちに入った」ことを検知 →
 * A が第三の枠へ追加してコミット。本クラスはテストメソッドをトランザクションで包まない。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261010-1432 P: 枠間移動と並行する割当追加の完全一致二重割当（並行契約）")
class ShiftSlotMoveConcurrentOverlapIT extends AbstractMySqlIntegrationTest {

    private static final String MOVE = "/api/v1/shifts/slots/{slotId}/assignments/move";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private ShiftSlotRepository slotRepository;
    @PersistenceContext
    private EntityManager em;

    @MockitoSpyBean
    private ShiftScheduleRepository scheduleRepository;

    private Long teamId;
    private Long adminId;
    private Long workerId;
    private Long scheduleId;
    private Long slotX;      // 移動元 09:00-12:00（worker 割当済み）
    private Long slotY;      // 移動先 13:00-17:00（空き）
    private Long slotThird;  // 第三の枠 13:00-17:00（Y と完全一致・空き）

    @BeforeEach
    void setUp() {
        long nonce = System.nanoTime();
        teamId = insertTeam("P 並行移動IT チーム" + nonce);
        adminId = insertUser("p-move-admin-" + nonce + "@example.com");
        workerId = insertUser("p-move-worker-" + nonce + "@example.com");
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
            MembershipTestHelper.insertMembership(em, workerId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        });
        scheduleId = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamId)
                .title("P 並行移動")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 11, 1))
                .endDate(LocalDate.of(2026, 11, 7))
                .status(ShiftScheduleStatus.ADJUSTING)
                .requestDeadline(LocalDateTime.of(2099, 1, 1, 0, 0))
                .createdBy(adminId)
                .build()).getId();
        slotX = insertSlot(LocalTime.of(9, 0), LocalTime.of(12, 0), 2, List.of(workerId));
        slotY = insertSlot(LocalTime.of(13, 0), LocalTime.of(17, 0), 1, List.of());
        slotThird = insertSlot(LocalTime.of(13, 0), LocalTime.of(17, 0), 1, List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("DELETE FROM shift_slots WHERE schedule_id = ?", scheduleId);
        jdbcTemplate.update("DELETE FROM shift_schedules WHERE id = ?", scheduleId);
        jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?", adminId);
        jdbcTemplate.update("DELETE FROM memberships WHERE user_id IN (?, ?)", adminId, workerId);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", adminId, workerId);
        jdbcTemplate.update("DELETE FROM teams WHERE id = ?", teamId);
    }

    @Test
    @DisplayName("親ロック待ちの間に別操作が移動先と完全一致の第三の枠へ同一ユーザーを追加しコミットすると、移動は409 SHIFT_042で拒否され二重割当が残らない")
    void 親ロック待ちの間に追加された完全一致の第三枠を見逃さない() throws Exception {
        long vx = versionOf(slotX);
        long vy = versionOf(slotY);
        CountDownLatch operationAHoldsLock = new CountDownLatch(1);
        CountDownLatch moveWaitsForLock = new CountDownLatch(1);
        AtomicReference<Thread> moveThread = new AtomicReference<>();

        // 移動処理スレッドが親ロックを要求した瞬間（＝通常読み取りでスナップショット確定済み）を検知する。
        Answer<?> delegateToReal = mockingDetails(scheduleRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            if (Thread.currentThread() == moveThread.get()) {
                moveWaitsForLock.countDown();
            }
            return delegateToReal.answer(invocation);
        }).when(scheduleRepository).findByIdForUpdate(scheduleId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 操作 A: 親ロックを保持したまま、移動処理がロック待ちに入ってから第三の枠へ追加してコミットする
            Future<?> operationA = executor.submit(() -> {
                new TransactionTemplate(txManager).executeWithoutResult(s -> {
                    scheduleRepository.findByIdForUpdate(scheduleId).orElseThrow();
                    operationAHoldsLock.countDown();
                    try {
                        assertThat(moveWaitsForLock.await(30, TimeUnit.SECONDS)).as("移動処理が親ロック待ちに入る").isTrue();
                        Thread.sleep(500); // 移動処理がロック待機に入りきるのを待つ
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    jdbcTemplate.update(
                            "UPDATE shift_slots SET assigned_user_ids = ?, version = version + 1 WHERE id = ?",
                            ShiftAssignedUserIds.serialize(List.of(workerId)), slotThird);
                });
                return null;
            });
            assertThat(operationAHoldsLock.await(30, TimeUnit.SECONDS)).isTrue();

            Future<MockHttpServletResponse> move = executor.submit(() -> {
                moveThread.set(Thread.currentThread());
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(adminId.toString(), null, List.of()));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("targetSlotId", slotY);
                body.put("userId", workerId);
                body.put("sourceSlotVersion", vx);
                body.put("targetSlotVersion", vy);
                return mockMvc.perform(post(MOVE, slotX)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                        .andReturn().getResponse();
            });

            operationA.get(60, TimeUnit.SECONDS);
            MockHttpServletResponse response = move.get(60, TimeUnit.SECONDS);

            assertThat(response.getStatus()).as("完全一致の二重割当は409で拒否").isEqualTo(409);
            assertThat((String) JsonPath.read(response.getContentAsString(), "$.error.code")).isEqualTo("SHIFT_042");
        } finally {
            executor.shutdownNow();
        }

        assertThat(assignedOf(slotThird)).as("操作 A の追加は確定している").containsExactly(workerId);
        assertThat(assignedOf(slotY)).as("移動先に二重割当が残らない").isEmpty();
        assertThat(assignedOf(slotX)).as("移動元の割当は失われない").containsExactly(workerId);
    }

    private Long versionOf(Long slotId) {
        return jdbcTemplate.queryForObject("SELECT version FROM shift_slots WHERE id = ?", Long.class, slotId);
    }

    private List<Long> assignedOf(Long slotId) {
        return ShiftAssignedUserIds.parse(
                slotRepository.findById(slotId).orElseThrow().getAssignedUserIds());
    }

    private Long insertSlot(LocalTime start, LocalTime end, int required, List<Long> assigned) {
        return slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(scheduleId)
                .slotDate(LocalDate.of(2026, 11, 2))
                .startTime(start)
                .endTime(end)
                .requiredCount(required)
                .assignedUserIds(assigned.isEmpty() ? null : ShiftAssignedUserIds.serialize(assigned))
                .build()).getId();
    }

    private Long insertTeam(String name) {
        jdbcTemplate.update(
                "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                        + "created_at, updated_at) VALUES (?, 'PUBLIC', 1, 0, 0, "
                        + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())", name);
        return jdbcTemplate.queryForObject("SELECT id FROM teams WHERE name = ?", Long.class, name);
    }

    private Long insertUser(String email) {
        jdbcTemplate.update(
                "INSERT INTO users (email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, created_at, updated_at) "
                        + "VALUES (?, 'P', 'テスト', 'P テスト', 'ACTIVE', 1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())",
                email);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }
}
