package com.mannschaft.app.shift;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.shift.entity.ShiftAssignmentRunEntity;
import com.mannschaft.app.shift.repository.ShiftAssignmentRunRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.stubbing.Answer;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 *   <li>K1（AC-17）: 認可の<b>後</b>・tx の<b>前</b>に親スケジュールを論理削除し（Gate / AccessControlService の spy で
 *       決定的に挿入）、<b>本人以外のすべての経路</b>（swap の作成・承諾・承認・管理者取消、change の作成・一覧・審査・
 *       管理者詳細、auto-assign の実行・確定・破棄・一覧・詳細・目視確認）が解決時と同じコード・message の 404 になり、
 *       DB（全列＝version・更新時刻まで）が不変であること。本人の経路は是正前から親に依存しないので対象外。</li>
 *   <li>K6（AC-19）: 別 tx が親スケジュール行を {@code FOR UPDATE} で握っていても、部外者は run・確定・破棄のどれも
 *       待たずに 404。許可された管理者は認可の後に {@code FOR UPDATE} へ進む（要求スレッドが {@code findByIdForUpdate} の中で塞がれている状態が
 *       現れるまで条件待ちして確認する。固定 sleep は使わない）。</li>
 *   <li>AC-18: 許可経路で、認可のクエリ（Gate）・scope 解決・tx 本体を分けて数え、合計が一致し、
 *       自ドメインの読み直しがちょうど 1 巡（scope と同数）増えていること。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("shift 認可ファサード型（W2）の競合・ロック順・クエリ回数")
class ShiftTxFacadeRaceAndQueryIT extends AbstractMySqlIntegrationTest {

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
    private ShiftAssignmentRunRepository runRepository;
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
    /** Gate を通らない認可（詳細・目視確認・変更依頼の管理者詳細）の「後」に割り込むための spy。 */
    @MockitoSpyBean
    private AccessControlService accessControlService;

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

    /** 親を消すタイミング（= どの認可呼び出しの「直後」か）。 */
    private enum Hook {
        GATE_MEMBER, GATE_ADMIN, GATE_OWNER_OR_ADMIN, GATE_PERMITTED,
        /** Gate を使わず AccessControlService を直接呼ぶ経路（詳細・目視確認・変更依頼の管理者詳細）。 */
        ACL_ADMIN_CHECK
    }

    private record Req(Long userId, MockHttpServletRequestBuilder request) { }

    /**
     * 1 経路 = 1 ケース。{@code expected} は是正前の応答（各 Facade の認可表）と同じコード。
     *
     * <p><b>対象外（本人の経路）:</b> swap の本人取消・change の取下げ・本人の change 詳細は、是正前から
     * 親（枠・スケジュール）の存在に依存しない仕様（認可表の「本人以外」注記）なので、親が消えても 404 にならない。
     * ここには含めず、本人以外の経路だけを固定する。</p>
     */
    private record ParentDeletedCase(
            String name, Hook hook, ShiftErrorCode expected, Function<ShiftTxFacadeRaceAndQueryIT, Req> req) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<ParentDeletedCase> parentDeletedCases() {
        return Stream.of(
                // --- swap ---
                new ParentDeletedCase("swap 作成（枠→スケジュール）", Hook.GATE_MEMBER,
                        ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND,
                        t -> new Req(t.memberId, t.postJson("/api/v1/shifts/swap-requests",
                                Map.of("slotId", t.slotId, "openCall", true, "reason", "競合テスト")))),
                new ParentDeletedCase("swap 承諾", Hook.GATE_MEMBER, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND,
                        t -> new Req(t.member2Id, post("/api/v1/shifts/swap-requests/{id}/accept", t.newSwap()))),
                new ParentDeletedCase("swap 承認（resolve）", Hook.GATE_ADMIN, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND,
                        t -> new Req(t.adminId, t.postJson("/api/v1/shifts/swap-requests/" + t.newSwap() + "/resolve",
                                Map.of("action", "APPROVE", "adminNote", "承認")))),
                new ParentDeletedCase("swap 管理者による取消（申請者以外）", Hook.GATE_OWNER_OR_ADMIN,
                        ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND,
                        t -> new Req(t.adminId, delete("/api/v1/shifts/swap-requests/{id}", t.newSwap()))),
                // --- change request ---
                new ParentDeletedCase("change 作成", Hook.GATE_MEMBER, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND,
                        t -> new Req(t.memberId, t.postJson("/api/v1/shifts/change-requests",
                                Map.of("scheduleId", t.scheduleId, "requestType", "INDIVIDUAL_SWAP",
                                        "reason", "競合テスト")))),
                new ParentDeletedCase("change 一覧", Hook.GATE_PERMITTED, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND,
                        t -> new Req(t.memberId, get("/api/v1/shifts/change-requests")
                                .param("scheduleId", String.valueOf(t.scheduleId)))),
                new ParentDeletedCase("change 審査", Hook.GATE_ADMIN, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND,
                        t -> new Req(t.adminId, patch("/api/v1/shifts/change-requests/{id}/review", t.newChangeRequest())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(t.toJson(Map.of("decision", "ACCEPTED", "reviewComment", "承認",
                                        "version", 0))))),
                new ParentDeletedCase("change 管理者による詳細（依頼者以外）", Hook.ACL_ADMIN_CHECK,
                        ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND,
                        t -> new Req(t.adminId, get("/api/v1/shifts/change-requests/{id}", t.newChangeRequest()))),
                // --- auto-assign ---
                new ParentDeletedCase("auto-assign 実行", Hook.GATE_ADMIN, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND,
                        t -> new Req(t.adminId, t.postJson("/api/v1/shifts/schedules/" + t.scheduleId + "/auto-assign",
                                Map.of("strategy", "GREEDY_V1")))),
                new ParentDeletedCase("auto-assign 確定", Hook.GATE_ADMIN, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND,
                        t -> {
                            Long runId = t.newRun(ShiftAssignmentRunStatus.CONFIRMED);
                            return new Req(t.adminId, t.postJson(
                                    "/api/v1/shifts/schedules/" + t.scheduleId + "/auto-assign/confirm",
                                    Map.of("runId", runId, "assignmentIds", List.of(1L), "scheduleVersion", 0)));
                        }),
                new ParentDeletedCase("auto-assign 破棄", Hook.GATE_ADMIN, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND,
                        t -> new Req(t.adminId, delete("/api/v1/shifts/schedules/{id}/auto-assign", t.scheduleId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(String.valueOf(t.newRun(ShiftAssignmentRunStatus.SUCCEEDED))))),
                new ParentDeletedCase("auto-assign 履歴一覧", Hook.GATE_ADMIN, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND,
                        t -> new Req(t.adminId, get("/api/v1/shifts/schedules/{id}/assignment-runs", t.scheduleId))),
                new ParentDeletedCase("auto-assign 実行ログ詳細", Hook.ACL_ADMIN_CHECK,
                        ShiftErrorCode.ASSIGNMENT_RUN_NOT_FOUND,
                        t -> new Req(t.adminId, get("/api/v1/shifts/assignment-runs/{id}",
                                t.newRun(ShiftAssignmentRunStatus.SUCCEEDED)))),
                new ParentDeletedCase("auto-assign 目視確認", Hook.ACL_ADMIN_CHECK,
                        ShiftErrorCode.ASSIGNMENT_RUN_NOT_FOUND,
                        t -> new Req(t.adminId, post("/api/v1/shifts/assignment-runs/{id}/confirm-visual-review",
                                t.newRun(ShiftAssignmentRunStatus.SUCCEEDED))))
        );
    }

    @ParameterizedTest(name = "K1: {0} — 認可後に親が消えたら 404・DB 不変")
    @MethodSource("parentDeletedCases")
    @DisplayName("K1: 本人以外の全経路で、認可後・tx 前の親の論理削除は解決時と同じコードの 404 になり DB は変わらない")
    void 認可後に親が消えたら404でDB不変(ParentDeletedCase testCase) throws Exception {
        Req req = testCase.req().apply(this);
        String before = snapshot();
        installHookAfterAuthorization(testCase.hook());

        setAuth(req.userId());
        MvcResult result = mockMvc.perform(req.request()).andReturn();

        // フックが実際に走った（親が消えた）ことの確認。走っていなければ 404 の根拠が崩れるので先に見る。
        assertThat(scheduleIsSoftDeleted()).as("フックが走り親スケジュールが論理削除されていること").isTrue();
        assertNotFound(result, testCase.expected());
        assertThat(snapshot()).as("拒否後も swap / change / run / 割当 / 枠の行は version・更新時刻まで不変").isEqualTo(before);
    }

    private void installHookAfterAuthorization(Hook hook) {
        Answer<Object> afterReal = inv -> {
            Object result = inv.callRealMethod();
            softDeleteSchedule();
            return result;
        };
        switch (hook) {
            case GATE_MEMBER -> Mockito.doAnswer(afterReal).when(gate)
                    .requireMemberOrConceal(any(), any(), any(), any(), anyBoolean());
            case GATE_ADMIN -> Mockito.doAnswer(afterReal).when(gate)
                    .requireAdminOrConceal(any(), any(), any(), any());
            case GATE_OWNER_OR_ADMIN -> Mockito.doAnswer(afterReal).when(gate)
                    .requireOwnerOrAdminOrConceal(any(), any(), any(), any(), any());
            case GATE_PERMITTED -> Mockito.doAnswer(afterReal).when(gate)
                    .requireOrConceal(any(), any(), any(), any(), any(), any());
            case ACL_ADMIN_CHECK -> Mockito.doAnswer(inv -> {
                Object result = inv.callRealMethod();
                // Facade 以外の呼び出し元（フィルタ・フラグ判定など）が先に呼んでも反応しないよう、
                // Facade の直下で呼ばれたときだけ親を消す。
                if (Arrays.stream(Thread.currentThread().getStackTrace())
                        .anyMatch(e -> e.getClassName().endsWith("Facade"))) {
                    softDeleteSchedule();
                }
                return result;
            }).when(accessControlService).isAdminOrAbove(any(), any(), any());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // K6 / AC-19: FOR UPDATE は認可の後（run・confirm・revoke。部外者は取らない・許可者は認可の後に取る）
    // ═════════════════════════════════════════════════════════════════════

    private enum LockedOp {
        RUN("実行", ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND, 201),
        CONFIRM("確定", ShiftErrorCode.ASSIGNMENT_RUN_NOT_FOUND, 200),
        REVOKE("破棄", ShiftErrorCode.ASSIGNMENT_RUN_NOT_FOUND, 204);

        final String label;
        final ShiftErrorCode outsiderCode;
        final int adminStatus;

        LockedOp(String label, ShiftErrorCode outsiderCode, int adminStatus) {
            this.label = label;
            this.outsiderCode = outsiderCode;
            this.adminStatus = adminStatus;
        }
    }

    private MockHttpServletRequestBuilder lockedOpRequest(LockedOp op) {
        return switch (op) {
            case RUN -> postJson("/api/v1/shifts/schedules/" + scheduleId + "/auto-assign",
                    Map.of("strategy", "GREEDY_V1"));
            case CONFIRM -> postJson("/api/v1/shifts/schedules/" + scheduleId + "/auto-assign/confirm",
                    Map.of("runId", newRun(ShiftAssignmentRunStatus.CONFIRMED),
                            "assignmentIds", List.of(1L), "scheduleVersion", 0));
            case REVOKE -> delete("/api/v1/shifts/schedules/{id}/auto-assign", scheduleId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(String.valueOf(newRun(ShiftAssignmentRunStatus.SUCCEEDED)));
        };
    }

    @ParameterizedTest(name = "K6: 自動割当の{0}")
    @EnumSource(LockedOp.class)
    @DisplayName("K6: 別 tx が親スケジュール行を FOR UPDATE していても、部外者は待たずに 404（ロックを取りに行かない）")
    void 部外者は親行ロックを待たずに404(LockedOp op) throws Exception {
        MockHttpServletRequestBuilder request = lockedOpRequest(op);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = holdScheduleLock(locked, release);
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            // assertTimeoutPreemptively は別スレッドで実行するため、認証はラムダの中で張る。
            // 部外者が FOR UPDATE を取りに行けば、ここで保持中の行ロックに塞がれて 8 秒以内に返らない。
            MvcResult result = assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
                setAuth(outsiderId);
                return mockMvc.perform(request).andReturn();
            });
            assertNotFound(result, op.outsiderCode);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest(name = "K6: 自動割当の{0}")
    @EnumSource(LockedOp.class)
    @DisplayName("K6: 許可された管理者は認可の後に FOR UPDATE へ進む（FOR UPDATE の中で塞がれていることを確認し、解放後に完了する）")
    void 管理者は認可の後に親行ロックを取りに行く(LockedOp op) throws Exception {
        MockHttpServletRequestBuilder request = lockedOpRequest(op);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = holdScheduleLock(locked, release);
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            AtomicReference<Thread> requestThread = new AtomicReference<>();
            CompletableFuture<Integer> response = CompletableFuture.supplyAsync(() -> {
                requestThread.set(Thread.currentThread());
                try {
                    setAuth(adminId);
                    return mockMvc.perform(request).andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }, executor);
            // 固定の待機ではなく、「要求スレッドが findByIdForUpdate の中（= 行ロック待ち）にいる」まで条件待ちする。
            // 部外者の拒否経路はこのメソッドに入らないので、ここに到達した = 認可を通過した後に FOR UPDATE へ進んだ証拠。
            // （information_schema.innodb_trx は PROCESS 権限が要り、テスト用ユーザーでは読めないため使わない）
            Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(20))
                    .until(() -> response.isDone() || isInsideFindByIdForUpdate(requestThread.get()));
            assertThat(response.isDone()).as("ロック保持中に管理者の要求が完了してはならない").isFalse();
            assertThat(isInsideFindByIdForUpdate(requestThread.get())).isTrue();
            release.countDown();
            assertThat(response.get(30, TimeUnit.SECONDS)).isEqualTo(op.adminStatus);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    /** スレッドが今 {@code findByIdForUpdate}（親行の FOR UPDATE）の呼び出しの中にいるか。 */
    private static boolean isInsideFindByIdForUpdate(Thread thread) {
        return thread != null && Arrays.stream(thread.getStackTrace())
                .anyMatch(e -> "findByIdForUpdate".equals(e.getMethodName()));
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

    private void assertNotFound(MvcResult result, ShiftErrorCode expected) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(content).isEqualTo(404);
        assertThat((String) JsonPath.read(content, "$.error.code")).isEqualTo(expected.getCode());
        assertThat((String) JsonPath.read(content, "$.error.message")).isEqualTo(expected.getMessage());
    }

    /**
     * 親スケジュールを論理削除してコミットする。フックは Gate（{@code @Transactional(readOnly=true)}）の呼び出しの中で
     * 走るため、既存 tx に合流させず {@code REQUIRES_NEW} の別接続・別 tx で書く（合流すると readOnly の tx へ UPDATE
     * してしまい 500 になる）。
     */
    private void softDeleteSchedule() {
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.executeWithoutResult(s -> em.createNativeQuery(
                        "UPDATE shift_schedules SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", scheduleId).executeUpdate());
    }

    private boolean scheduleIsSoftDeleted() {
        return tx.execute(s -> ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM shift_schedules WHERE id = :id AND deleted_at IS NOT NULL")
                .setParameter("id", scheduleId).getSingleResult()).longValue() == 1L);
    }

    /**
     * 親（スケジュール）の配下の行を、全列（version・更新時刻を含む）そのまま文字列にして連結する。
     * 論理削除したスケジュール行自身は含めない。拒否・不在で 1 列でも変われば差が出る。
     */
    private String snapshot() {
        return tx.execute(s -> Stream.of(
                        "SELECT * FROM shift_swap_requests WHERE slot_id IN "
                                + "(SELECT id FROM shift_slots WHERE schedule_id = :sid) ORDER BY id",
                        "SELECT * FROM shift_change_requests WHERE schedule_id = :sid ORDER BY id",
                        "SELECT * FROM shift_assignment_runs WHERE schedule_id = :sid ORDER BY id",
                        "SELECT * FROM shift_assignments WHERE run_id IN "
                                + "(SELECT id FROM shift_assignment_runs WHERE schedule_id = :sid) ORDER BY id",
                        "SELECT * FROM shift_slots WHERE schedule_id = :sid ORDER BY id")
                .map(sql -> {
                    @SuppressWarnings("unchecked")
                    List<Object> rows = em.createNativeQuery(sql).setParameter("sid", scheduleId).getResultList();
                    return rows.stream()
                            .map(r -> r instanceof Object[] cols ? Arrays.deepToString(cols) : String.valueOf(r))
                            .collect(Collectors.joining(",", "[", "]"));
                })
                .collect(Collectors.joining("\n")));
    }

    /** 申請者 memberId の PENDING な交代リクエストを 1 件コミットして ID を返す。 */
    private Long newSwap() {
        return tx.execute(s -> swapRepository.save(buildSwap(memberId)).getId());
    }

    /** 依頼者 memberId の変更依頼を 1 件コミットして ID を返す。 */
    private Long newChangeRequest() {
        return tx.execute(s -> changeRequestRepository.save(ShiftChangeRequestEntity.builder()
                .scheduleId(scheduleId)
                .requestType(ChangeRequestType.INDIVIDUAL_SWAP)
                .requestedBy(memberId)
                .reason("競合テスト")
                .build()).getId());
    }

    private Long newRun(ShiftAssignmentRunStatus status) {
        return tx.execute(s -> runRepository.save(ShiftAssignmentRunEntity.builder()
                .scheduleId(scheduleId)
                .strategy(AssignmentStrategyType.GREEDY_V1)
                .status(status)
                .triggeredBy(adminId)
                .slotsTotal(1)
                .slotsFilled(0)
                .build()).getId());
    }

    private MockHttpServletRequestBuilder postJson(String url, Object body) {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(toJson(body));
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
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
