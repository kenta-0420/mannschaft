package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftChangeRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import com.mannschaft.app.shift.repository.ShiftChangeRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.repository.ShiftSwapRequestRepository;
import com.mannschaft.app.shift.service.ShiftSwapFacade;
import com.mannschaft.app.shift.service.ShiftSwapService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 認可をトランザクションの外へ出した型（CMP-260923-0954 W2 / plan4）の<b>競合・ロック順・クエリ回数</b>の契約テスト。
 *
 * <p>既存の {@code *ScopeContractIT} は {@code @Transactional}（テスト全体が 1 tx）で、Facade と tx 本体が
 * 同じ tx に畳まれる。本クラスは<b>意図的に {@code @Transactional} を付けず</b>、データをコミットして
 * 実際に「認可（tx の外）→ tx」の 2 段を踏ませる。終了時に自分で作った行を消す。</p>
 *
 * <ul>
 *   <li>K1（AC-17）: 認可の<b>後</b>・tx の<b>前</b>に親スケジュールを論理削除し（Gate の spy で決定的に挿入）、
 *       swap・change・auto-assign が解決時と同じコードの 404 になり、DB（status・version・行数）が不変であること。</li>
 *   <li>K6（AC-19）: 別 tx が親スケジュール行を {@code FOR UPDATE} で握っていても、部外者は待たずに 404。
 *       許可された管理者は認可の後に {@code FOR UPDATE} へ進むので待たされる（= ロックは認可の後）。</li>
 *   <li>AC-18: 許可経路で、認可のクエリ（Gate）・scope 解決・tx 本体を分けて数え、合計が一致し、
 *       自ドメインの読み直しがちょうど 1 巡（scope と同数）増えていること。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("shift 認可ファサード型（W2）の競合・ロック順・クエリ回数")
class ShiftTxFacadeRaceAndQueryIT extends AbstractMySqlIntegrationTest {

    private static final String SCHEDULE_NOT_FOUND_CODE = "SHIFT_001";
    private static final String SCHEDULE_NOT_FOUND_MESSAGE = "シフトスケジュールが見つかりません";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ShiftScheduleRepository scheduleRepository;
    @Autowired
    private ShiftSlotRepository slotRepository;
    @Autowired
    private ShiftSwapRequestRepository swapRepository;
    @Autowired
    private ShiftChangeRequestRepository changeRequestRepository;
    @Autowired
    private FeatureFlagRepository featureFlagRepository;
    @Autowired
    private CacheManager cacheManager;
    @Autowired
    private ShiftSwapFacade swapFacade;
    @Autowired
    private ShiftSwapService swapService;

    /** 認可の「後」に割り込むための spy（実処理は呼んだ上で、直後にフックを走らせる）。 */
    @MockitoSpyBean
    private ScopeConcealingAccessGate gate;

    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate tx;
    private ExecutorService executor;

    private String suffix;
    private Long teamId;
    private Long adminId;
    private Long memberId;
    private Long member2Id;
    private Long outsiderId;
    private Long scheduleId;
    private Long slotId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        executor = Executors.newCachedThreadPool();
        suffix = Long.toHexString(System.nanoTime());
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, "FEATURE_SHIFT_AUTO_ASSIGN_ENABLED");

        tx.executeWithoutResult(status -> {
            teamId = insertTeam("W2FACADE-" + suffix);
            adminId = insertUser("w2f-admin-" + suffix + "@example.com");
            memberId = insertUser("w2f-member-" + suffix + "@example.com");
            member2Id = insertUser("w2f-member2-" + suffix + "@example.com");
            outsiderId = insertUser("w2f-outsider-" + suffix + "@example.com");
            MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
            MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, member2Id, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            scheduleId = scheduleRepository.save(ShiftScheduleEntity.builder()
                    .teamId(teamId)
                    .title("W2FACADE " + suffix)
                    .periodType(ShiftPeriodType.WEEKLY)
                    .startDate(LocalDate.of(2026, 3, 1))
                    .endDate(LocalDate.of(2026, 3, 7))
                    .status(ShiftScheduleStatus.DRAFT)
                    .createdBy(adminId)
                    .build()).getId();
            slotId = slotRepository.save(ShiftSlotEntity.builder()
                    .scheduleId(scheduleId)
                    .slotDate(LocalDate.of(2026, 3, 2))
                    .startTime(LocalTime.of(9, 0))
                    .endTime(LocalTime.of(18, 0))
                    .requiredCount(1)
                    .build()).getId();
        });
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        SecurityContextHolder.clearContext();
        FeatureFlagTestSupport.clearFlagCaches(cacheManager);
        tx.executeWithoutResult(status -> {
            deleteWhere("shift_assignments", "run_id IN (SELECT id FROM shift_assignment_runs WHERE schedule_id = :sid)");
            deleteWhere("shift_assignment_runs", "schedule_id = :sid");
            deleteWhere("shift_swap_requests", "slot_id IN (SELECT id FROM shift_slots WHERE schedule_id = :sid)");
            deleteWhere("shift_change_requests", "schedule_id = :sid");
            deleteWhere("shift_slots", "schedule_id = :sid");
            deleteWhere("shift_schedules", "id = :sid");
            em.createNativeQuery("DELETE FROM user_roles WHERE team_id = :tid").setParameter("tid", teamId)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM memberships WHERE scope_type = 'TEAM' AND scope_id = :tid")
                    .setParameter("tid", teamId).executeUpdate();
            em.createNativeQuery("DELETE FROM users WHERE email LIKE :p").setParameter("p", "w2f-%-" + suffix + "@%")
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM teams WHERE id = :tid").setParameter("tid", teamId).executeUpdate();
        });
    }

    private void deleteWhere(String table, String where) {
        em.createNativeQuery("DELETE FROM " + table + " WHERE " + where).setParameter("sid", scheduleId)
                .executeUpdate();
    }

    // ═════════════════════════════════════════════════════════════════════
    // K1 / AC-17: 認可の後・tx の前に親を論理削除 → 解決時と同じコードの 404・DB 不変
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("K1: 交代リクエストの承諾 — 認可後に親スケジュールが消えたら 404 SHIFT_001・swap 行は不変（version まで）")
    void 交代承諾_認可後に親が消えたら404でDB不変() throws Exception {
        Long swapId = tx.execute(s -> swapRepository.save(buildSwap(memberId)).getId());
        String before = swapRow(swapId);
        Mockito.doAnswer(inv -> {
            Object result = inv.callRealMethod();
            softDeleteSchedule();
            return result;
        }).when(gate).requireMemberOrConceal(any(), any(), any(), any(), anyBoolean());

        setAuth(member2Id);
        MvcResult result = mockMvc.perform(post("/api/v1/shifts/swap-requests/{id}/accept", swapId))
                .andReturn();

        assertScheduleNotFound(result);
        assertThat(swapRow(swapId)).as("swap 行は status・accepter・version とも不変").isEqualTo(before);
    }

    @Test
    @DisplayName("K1: 変更依頼の作成 — 認可後に親スケジュールが消えたら 404 SHIFT_001・行は増えない")
    void 変更依頼作成_認可後に親が消えたら404でDB不変() throws Exception {
        long before = countChangeRequests();
        Mockito.doAnswer(inv -> {
            Object result = inv.callRealMethod();
            softDeleteSchedule();
            return result;
        }).when(gate).requireMemberOrConceal(any(), any(), any(), any(), anyBoolean());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scheduleId", scheduleId);
        body.put("requestType", "INDIVIDUAL_SWAP");
        body.put("reason", "競合テスト");
        setAuth(memberId);
        MvcResult result = mockMvc.perform(post("/api/v1/shifts/change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();

        assertScheduleNotFound(result);
        assertThat(countChangeRequests()).isEqualTo(before);
    }

    @Test
    @DisplayName("K1: 変更依頼の審査 — 認可後に親スケジュールが消えたら 404 SHIFT_001・依頼行は不変（version まで）")
    void 変更依頼審査_認可後に親が消えたら404でDB不変() throws Exception {
        Long requestId = tx.execute(s -> changeRequestRepository.save(ShiftChangeRequestEntity.builder()
                .scheduleId(scheduleId)
                .requestType(ChangeRequestType.INDIVIDUAL_SWAP)
                .requestedBy(memberId)
                .reason("競合テスト")
                .build()).getId());
        String before = changeRequestRow(requestId);
        Mockito.doAnswer(inv -> {
            Object result = inv.callRealMethod();
            softDeleteSchedule();
            return result;
        }).when(gate).requireAdminOrConceal(any(), any(), any(), any());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", "ACCEPTED");
        body.put("reviewComment", "承認");
        body.put("version", 0);
        setAuth(adminId);
        MvcResult result = mockMvc.perform(patch("/api/v1/shifts/change-requests/{id}/review", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();

        assertScheduleNotFound(result);
        assertThat(changeRequestRow(requestId)).isEqualTo(before);
    }

    @Test
    @DisplayName("K1: 自動割当の実行 — 認可後に親スケジュールが消えたら 404 SHIFT_001・run 行は作られない")
    void 自動割当実行_認可後に親が消えたら404でDB不変() throws Exception {
        long before = countRuns();
        Mockito.doAnswer(inv -> {
            Object result = inv.callRealMethod();
            softDeleteSchedule();
            return result;
        }).when(gate).requireAdminOrConceal(any(), any(), any(), any());

        setAuth(adminId);
        MvcResult result = mockMvc.perform(post("/api/v1/shifts/schedules/{id}/auto-assign", scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("strategy", "GREEDY_V1"))))
                .andReturn();

        assertScheduleNotFound(result);
        assertThat(countRuns()).isEqualTo(before);
    }

    // ═════════════════════════════════════════════════════════════════════
    // K6 / AC-19: FOR UPDATE は認可の後
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("K6: 別 tx が親スケジュール行を FOR UPDATE していても、部外者は待たずに 404（ロックを取りに行かない）")
    void 部外者は親行ロックを待たずに404() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = holdScheduleLock(locked, release);
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            setAuth(outsiderId);
            MvcResult result = assertTimeoutPreemptively(Duration.ofSeconds(8), () ->
                    mockMvc.perform(post("/api/v1/shifts/schedules/{id}/auto-assign", scheduleId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(Map.of("strategy", "GREEDY_V1"))))
                            .andReturn());
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.error.code"))
                    .isEqualTo(SCHEDULE_NOT_FOUND_CODE);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("K6: 許可された管理者は認可の後に FOR UPDATE へ進む（他 tx のロックが解けるまで待たされる）")
    void 管理者は認可の後に親行ロックを取りに行く() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = holdScheduleLock(locked, release);
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Integer> request = CompletableFuture.supplyAsync(() -> {
                try {
                    setAuth(adminId);
                    return mockMvc.perform(post("/api/v1/shifts/schedules/{id}/auto-assign", scheduleId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(Map.of("strategy", "GREEDY_V1"))))
                            .andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }, executor);
            // ロックを握られている間は完了しない = tx 内で親行の FOR UPDATE に到達して待っている
            Thread.sleep(2_000);
            AtomicBoolean doneWhileLocked = new AtomicBoolean(request.isDone());
            assertThat(doneWhileLocked.get()).as("ロック保持中に管理者のリクエストが完了してはならない").isFalse();
            release.countDown();
            assertThat(request.get(30, TimeUnit.SECONDS)).isEqualTo(201);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    private CompletableFuture<Void> holdScheduleLock(CountDownLatch locked, CountDownLatch release) {
        return CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            scheduleRepository.findByIdForUpdate(scheduleId).orElseThrow();
            locked.countDown();
            try {
                release.await(40, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }), executor);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-18: クエリ回数（認可 / scope 解決 / tx 本体を分けて数える）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-18: 交代承諾（許可経路）— 認可のクエリ数は不変、scope 解決は 3 本、自ドメインの読み直しがちょうど 1 巡増える")
    void 交代承諾_許可経路のクエリ回数() {
        Long swap1 = tx.execute(s -> swapRepository.save(buildSwap(memberId)).getId());
        Long swap2 = tx.execute(s -> swapRepository.save(buildSwap(memberId)).getId());
        Long swap3 = tx.execute(s -> swapRepository.save(buildSwap(memberId)).getId());
        // JIT・キャッシュの初回コストを測定から外す（同種の呼び出しを 1 回流しておく）
        gate.requireMemberOrConceal(member2Id, teamId, "TEAM", ShiftErrorCode.SWAP_REQUEST_NOT_FOUND, true);

        long gateQueries = countStatements(() ->
                gate.requireMemberOrConceal(member2Id, teamId, "TEAM", ShiftErrorCode.SWAP_REQUEST_NOT_FOUND, true));
        long scopeQueries = countStatements(() -> swapService.resolveSwapScope(swap1));
        long bodyQueries = countStatements(() -> swapService.acceptSwapRequest(swap2, member2Id));
        long facadeQueries = countStatements(() -> swapFacade.acceptSwapRequest(swap3, member2Id));
        System.out.printf("[AC-18] gate=%d scope=%d body=%d facade=%d%n",
                gateQueries, scopeQueries, bodyQueries, facadeQueries);

        // 認可（Gate）のクエリは Gate 単体で測った本数がそのまま Facade 全体に含まれる（下の合計の等式）。
        // Facade が認可のクエリを増やしていない（= 是正前と同じ認可クエリ数）ことは、この等式で固定する。
        // scope 解決: swap → slot → schedule の 3 本（是正前の解決経路と同じ）。
        assertThat(scopeQueries).as("scope 解決のクエリ数").isEqualTo(3L);
        // tx 本体: 読み直し（scope 解決と同じ 3 本）＋ UPDATE 1 本。是正前は読み直しが無かったので、増えたのは scope と同数の 1 巡。
        assertThat(bodyQueries).as("tx 本体のクエリ数").isEqualTo(scopeQueries + 1);
        // 合計 = scope 解決 + 認可 + tx 本体（Facade が余計なクエリを足していない）。
        assertThat(facadeQueries).as("Facade 全体").isEqualTo(scopeQueries + gateQueries + bodyQueries);
    }

    private long countStatements(Runnable action) {
        Statistics statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        action.run();
        return statistics.getPrepareStatementCount();
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void assertScheduleNotFound(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(content).isEqualTo(404);
        assertThat((String) JsonPath.read(content, "$.error.code")).isEqualTo(SCHEDULE_NOT_FOUND_CODE);
        assertThat((String) JsonPath.read(content, "$.error.message")).isEqualTo(SCHEDULE_NOT_FOUND_MESSAGE);
    }

    private void softDeleteSchedule() {
        tx.executeWithoutResult(s -> em.createNativeQuery(
                        "UPDATE shift_schedules SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", scheduleId).executeUpdate());
    }

    private String swapRow(Long id) {
        return tx.execute(s -> String.valueOf(em.createNativeQuery(
                        "SELECT CONCAT_WS('|', status, IFNULL(accepter_id, 'null'), version) "
                                + "FROM shift_swap_requests WHERE id = :id")
                .setParameter("id", id).getSingleResult()));
    }

    private String changeRequestRow(Long id) {
        return tx.execute(s -> String.valueOf(em.createNativeQuery(
                        "SELECT CONCAT_WS('|', status, IFNULL(reviewer_id, 'null'), version) "
                                + "FROM shift_change_requests WHERE id = :id")
                .setParameter("id", id).getSingleResult()));
    }

    private long countChangeRequests() {
        return tx.execute(s -> ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM shift_change_requests WHERE schedule_id = :sid")
                .setParameter("sid", scheduleId).getSingleResult()).longValue());
    }

    private long countRuns() {
        return tx.execute(s -> ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM shift_assignment_runs WHERE schedule_id = :sid")
                .setParameter("sid", scheduleId).getSingleResult()).longValue());
    }

    private ShiftSwapRequestEntity buildSwap(Long requesterId) {
        return ShiftSwapRequestEntity.builder()
                .slotId(slotId)
                .requesterId(requesterId)
                .status(SwapRequestStatus.PENDING)
                .reason("競合テスト用")
                .isOpenCall(true)
                .recipientMode("OPEN_CALL")
                .build();
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
                                + "VALUES (:email, 'W2FACADE', 'テスト', 'W2FACADE テスト', 'ACTIVE', "
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
