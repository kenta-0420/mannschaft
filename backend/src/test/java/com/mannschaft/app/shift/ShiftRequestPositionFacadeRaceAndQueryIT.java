package com.mannschaft.app.shift;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftPositionRepository;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.service.ShiftPositionFacade;
import com.mannschaft.app.shift.service.ShiftPositionService;
import com.mannschaft.app.shift.service.ShiftRequestFacade;
import com.mannschaft.app.shift.service.ShiftRequestService;
import com.mannschaft.app.shift.dto.UpdatePositionRequest;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.awaitility.Awaitility;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * 認可をトランザクションの外へ出した型（CMP-260923-0954 W1 の作り替え / plan4）の
 * シフト希望・ポジションにおける<b>競合・ロック順・クエリ回数</b>の契約テスト。
 *
 * <p>既存の {@code ShiftRequestPositionScopeContractIT} は {@code @Transactional}（テスト全体が 1 tx）で、
 * Facade と tx 本体が同じ tx に畳まれる。本クラスは<b>意図的に {@code @Transactional} を付けず</b>、
 * データをコミットして実際に「認可（tx の外）→ tx」の 2 段を踏ませる。終了時に自分で作った行を消す。
 * 書き方は W2 の {@code ShiftTxFacadeRaceAndQueryIT} に倣う（Gate の spy で「認可の後」に決定的に割り込み、
 * 待機は Awaitility とスタックの観測で行い固定 sleep は使わない）。</p>
 *
 * <ul>
 *   <li>K1（AC-17）: 認可の<b>後</b>・tx の<b>前</b>に親スケジュールを論理削除（希望系）／ポジションを物理削除（ポジション系）／対象の希望を物理削除（希望の更新・削除）し、
 *       <b>全経路</b>（希望の一覧・サマリー・提出・更新（本人・管理者）・削除（本人・管理者）、ポジションの更新・削除）が
 *       解決時と同じコード・message の 404 になり、DB（全列＝updated_at まで）が不変であること。
 *       希望の更新・削除は本人の経路でも是正前から親（スケジュール）に依存するため、本人も対象に含める
 *       （親に依存しない本人経路は shift のシフト希望・ポジションには無い）。
 *       ポジションの {@code ?teamId=} 一覧・作成はパスの teamId をそのまま認可に使い、実体を読み直す対象が無いので対象外。</li>
 *   <li>K6（AC-19）: 別 tx が親スケジュール行を {@code FOR UPDATE} で握っていても、部外者は提出・更新・削除のどれも
 *       待たずに 404。許可された利用者は認可の後に {@code FOR UPDATE} へ進む（要求スレッドが
 *       {@code findByIdForUpdate} の中で塞がれた状態を条件待ちで確認）。是正前は 3 操作とも FOR UPDATE が認可より先だった。</li>
 *   <li>AC-18: 許可経路で、認可のクエリ（Gate）・scope 解決・tx 本体を分けて数え、合計が一致し、
 *       自ドメインの読み直しがちょうど 1 巡（scope と同数）増えていること。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("shift 認可ファサード型（W1 作り替え: シフト希望・ポジション）の競合・ロック順・クエリ回数")
class ShiftRequestPositionFacadeRaceAndQueryIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private ShiftScheduleRepository scheduleRepository;
    @Autowired
    private ShiftRequestRepository requestRepository;
    @Autowired
    private ShiftPositionRepository positionRepository;
    @Autowired
    private ShiftRequestFacade requestFacade;
    @Autowired
    private ShiftRequestService requestService;
    @Autowired
    private ShiftPositionFacade positionFacade;
    @Autowired
    private ShiftPositionService positionService;

    /** 認可の「後」に割り込むための spy（実処理は呼んだ上で、直後にフックを走らせる）。 */
    @MockitoSpyBean
    private ScopeConcealingAccessGate gate;
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
    private Long bystanderPositionId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        executor = Executors.newCachedThreadPool();
        suffix = Long.toHexString(System.nanoTime());

        tx.executeWithoutResult(status -> {
            teamId = insertTeam("W1FACADE-" + suffix);
            adminId = insertUser("w1f-admin-" + suffix + "@example.com");
            memberId = insertUser("w1f-member-" + suffix + "@example.com");
            member2Id = insertUser("w1f-member2-" + suffix + "@example.com");
            outsiderId = insertUser("w1f-outsider-" + suffix + "@example.com");
            MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
            MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, member2Id, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            scheduleId = scheduleRepository.save(ShiftScheduleEntity.builder()
                    .teamId(teamId)
                    .title("W1FACADE " + suffix)
                    .periodType(ShiftPeriodType.WEEKLY)
                    .startDate(LocalDate.of(2026, 3, 1))
                    .endDate(LocalDate.of(2026, 3, 7))
                    .status(ShiftScheduleStatus.COLLECTING)
                    .requestDeadline(LocalDateTime.now().plusDays(30))
                    .createdBy(adminId)
                    .build()).getId();
            bystanderPositionId = positionRepository.save(ShiftPositionEntity.builder()
                    .teamId(teamId).name("bystander-" + suffix).displayOrder(9).isActive(true).build()).getId();
        });
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        SecurityContextHolder.clearContext();
        tx.executeWithoutResult(status -> {
            em.createNativeQuery("DELETE FROM shift_requests WHERE schedule_id = :sid")
                    .setParameter("sid", scheduleId).executeUpdate();
            em.createNativeQuery("DELETE FROM shift_positions WHERE team_id = :tid")
                    .setParameter("tid", teamId).executeUpdate();
            em.createNativeQuery("DELETE FROM shift_schedules WHERE id = :sid")
                    .setParameter("sid", scheduleId).executeUpdate();
            em.createNativeQuery("DELETE FROM user_roles WHERE team_id = :tid").setParameter("tid", teamId)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM memberships WHERE scope_type = 'TEAM' AND scope_id = :tid")
                    .setParameter("tid", teamId).executeUpdate();
            em.createNativeQuery("DELETE FROM users WHERE email LIKE :p").setParameter("p", "w1f-%-" + suffix + "@%")
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM teams WHERE id = :tid").setParameter("tid", teamId).executeUpdate();
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    // K1 / AC-17: 認可の後・tx の前に親（スケジュール）／対象（ポジション）が消える
    // ═════════════════════════════════════════════════════════════════════

    /** 親を消すタイミング（= どの認可呼び出しの「直後」か）と、何を消すか。 */
    private enum Hook {
        GATE_MEMBER, GATE_ADMIN, GATE_OWNER_OR_ADMIN
    }

    private enum Victim {
        /** 親スケジュールの論理削除（希望系の全経路）。 */
        SCHEDULE,
        /** ポジション行の物理削除（ポジションの更新・削除）。 */
        POSITION,
        /** 希望行そのものの物理削除（希望の更新・削除。親スケジュールは生きたまま、対象の希望だけが消える）。 */
        REQUEST
    }

    private record Req(Long userId, MockHttpServletRequestBuilder request) { }

    /** 1 経路 = 1 ケース。{@code expected} は是正前の応答（各 Facade の認可表）と同じコード。 */
    private record RaceCase(String name, Hook hook, Victim victim, ShiftErrorCode expected,
                            Function<ShiftRequestPositionFacadeRaceAndQueryIT, Req> req) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<RaceCase> raceCases() {
        return Stream.of(
                // --- シフト希望（親スケジュールが消える） ---
                new RaceCase("希望一覧（管理者）", Hook.GATE_ADMIN, Victim.SCHEDULE,
                        ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND,
                        t -> new Req(t.adminId, get("/api/v1/shifts/requests")
                                .param("scheduleId", String.valueOf(t.scheduleId)))),
                new RaceCase("希望サマリー（管理者）", Hook.GATE_ADMIN, Victim.SCHEDULE,
                        ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND,
                        t -> new Req(t.adminId, get("/api/v1/shifts/requests/summary")
                                .param("scheduleId", String.valueOf(t.scheduleId)))),
                new RaceCase("希望提出（メンバー）", Hook.GATE_MEMBER, Victim.SCHEDULE,
                        ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND,
                        t -> new Req(t.member2Id, t.postJson("/api/v1/shifts/requests",
                                Map.of("scheduleId", t.scheduleId, "slotDate", "2026-03-03",
                                        "preference", "PREFERRED", "note", "競合テスト")))),
                new RaceCase("希望更新（提出者本人）", Hook.GATE_OWNER_OR_ADMIN, Victim.SCHEDULE,
                        ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND,
                        t -> new Req(t.memberId, t.patchRequest(t.newRequest(t.memberId, LocalDate.of(2026, 3, 2))))),
                new RaceCase("希望更新（管理者）", Hook.GATE_OWNER_OR_ADMIN, Victim.SCHEDULE,
                        ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND,
                        t -> new Req(t.adminId, t.patchRequest(t.newRequest(t.memberId, LocalDate.of(2026, 3, 2))))),
                new RaceCase("希望削除（提出者本人）", Hook.GATE_OWNER_OR_ADMIN, Victim.SCHEDULE,
                        ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND,
                        t -> new Req(t.memberId, delete("/api/v1/shifts/requests/{id}",
                                t.newRequest(t.memberId, LocalDate.of(2026, 3, 2))))),
                new RaceCase("希望削除（管理者）", Hook.GATE_OWNER_OR_ADMIN, Victim.SCHEDULE,
                        ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND,
                        t -> new Req(t.adminId, delete("/api/v1/shifts/requests/{id}",
                                t.newRequest(t.memberId, LocalDate.of(2026, 3, 2))))),
                // --- シフト希望（対象の希望そのものが消える。親は生きている） ---
                new RaceCase("希望更新（提出者本人・希望が消える）", Hook.GATE_OWNER_OR_ADMIN, Victim.REQUEST,
                        ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND,
                        t -> new Req(t.memberId, t.patchRequest(t.newVictimRequest()))),
                new RaceCase("希望更新（管理者・希望が消える）", Hook.GATE_OWNER_OR_ADMIN, Victim.REQUEST,
                        ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND,
                        t -> new Req(t.adminId, t.patchRequest(t.newVictimRequest()))),
                new RaceCase("希望削除（提出者本人・希望が消える）", Hook.GATE_OWNER_OR_ADMIN, Victim.REQUEST,
                        ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND,
                        t -> new Req(t.memberId, delete("/api/v1/shifts/requests/{id}", t.newVictimRequest()))),
                new RaceCase("希望削除（管理者・希望が消える）", Hook.GATE_OWNER_OR_ADMIN, Victim.REQUEST,
                        ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND,
                        t -> new Req(t.adminId, delete("/api/v1/shifts/requests/{id}", t.newVictimRequest()))),
                // --- ポジション（対象行そのものが消える） ---
                new RaceCase("ポジション更新（管理者）", Hook.GATE_ADMIN, Victim.POSITION,
                        ShiftErrorCode.SHIFT_POSITION_NOT_FOUND,
                        t -> new Req(t.adminId, patch("/api/v1/shifts/positions/{id}", t.newPosition())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(t.toJson(Map.of("displayOrder", 5))))),
                new RaceCase("ポジション削除（管理者）", Hook.GATE_ADMIN, Victim.POSITION,
                        ShiftErrorCode.SHIFT_POSITION_NOT_FOUND,
                        t -> new Req(t.adminId, delete("/api/v1/shifts/positions/{id}", t.newPosition())))
        );
    }

    /** 現在のテストで「消される側」のポジション ID（ケースの req 生成時に newPosition が設定する）。 */
    private final AtomicReference<Long> victimPositionId = new AtomicReference<>();

    /** 現在のテストで「消される側」の希望 ID（Victim.REQUEST のケースの req 生成時に newVictimRequest が設定する）。 */
    private final AtomicReference<Long> victimRequestId = new AtomicReference<>();

    @ParameterizedTest(name = "K1: {0} — 認可後に対象/親が消えたら 404・DB 不変")
    @MethodSource("raceCases")
    @DisplayName("K1: 全経路で、認可後・tx 前の親/対象の削除は解決時と同じコードの 404 になり DB は変わらない")
    void 認可後に消えたら404でDB不変(RaceCase testCase) throws Exception {
        Req req = testCase.req().apply(this);
        String before = snapshot();
        installHookAfterAuthorization(testCase.hook(), testCase.victim());

        setAuth(req.userId());
        MvcResult result = mockMvc.perform(req.request()).andReturn();

        // フックが実際に走った（親/対象が消えた）ことの確認。走っていなければ 404 の根拠が崩れるので先に見る。
        if (testCase.victim() == Victim.SCHEDULE) {
            assertThat(scheduleIsSoftDeleted()).as("フックが走り親スケジュールが論理削除されていること").isTrue();
        } else if (testCase.victim() == Victim.REQUEST) {
            assertThat(requestExists(victimRequestId.get())).as("フックが走り希望が消えていること").isFalse();
        } else {
            assertThat(positionExists(victimPositionId.get())).as("フックが走りポジションが消えていること").isFalse();
        }
        assertNotFound(result, testCase.expected());
        assertThat(snapshot()).as("拒否後も希望・傍観ポジションの行は全列（updated_at まで）不変").isEqualTo(before);
        if (testCase.victim() == Victim.POSITION) {
            assertThat(positionExists(victimPositionId.get())).as("更新が消えた行を復活させていない").isFalse();
        }
        if (testCase.victim() == Victim.REQUEST) {
            assertThat(requestExists(victimRequestId.get())).as("更新・削除が消えた希望を復活・変更させていない").isFalse();
        }
    }

    private void installHookAfterAuthorization(Hook hook, Victim victim) {
        Runnable mutate = switch (victim) {
            case SCHEDULE -> this::softDeleteSchedule;
            case POSITION -> () -> hardDeletePosition(victimPositionId.get());
            case REQUEST -> () -> hardDeleteRequest(victimRequestId.get());
        };
        Answer<Object> afterReal = inv -> {
            Object result = inv.callRealMethod();
            mutate.run();
            return result;
        };
        switch (hook) {
            case GATE_MEMBER -> Mockito.doAnswer(afterReal).when(gate)
                    .requireMemberOrConceal(any(), any(), any(), any(), anyBoolean());
            case GATE_ADMIN -> Mockito.doAnswer(afterReal).when(gate)
                    .requireAdminOrConceal(any(), any(), any(), any());
            case GATE_OWNER_OR_ADMIN -> Mockito.doAnswer(afterReal).when(gate)
                    .requireOwnerOrAdminOrConceal(any(), any(), any(), any(), any());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // K6 / AC-19: FOR UPDATE は認可の後（提出・更新・削除）
    // ═════════════════════════════════════════════════════════════════════

    private enum LockedOp {
        SUBMIT("提出", ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND, 201),
        UPDATE("更新", ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND, 200),
        DELETE("削除", ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND, 204);

        final String label;
        final ShiftErrorCode outsiderCode;
        final int permittedStatus;

        LockedOp(String label, ShiftErrorCode outsiderCode, int permittedStatus) {
            this.label = label;
            this.outsiderCode = outsiderCode;
            this.permittedStatus = permittedStatus;
        }
    }

    private MockHttpServletRequestBuilder lockedOpRequest(LockedOp op) {
        return switch (op) {
            case SUBMIT -> postJson("/api/v1/shifts/requests",
                    Map.of("scheduleId", scheduleId, "slotDate", "2026-03-03",
                            "preference", "PREFERRED", "note", "ロックテスト"));
            case UPDATE -> patchRequest(newRequest(memberId, LocalDate.of(2026, 3, 2)));
            case DELETE -> delete("/api/v1/shifts/requests/{id}", newRequest(memberId, LocalDate.of(2026, 3, 2)));
        };
    }

    /** ロックの許可側の主体（提出は member2、更新・削除は提出者 member）。 */
    private Long permittedActor(LockedOp op) {
        return op == LockedOp.SUBMIT ? member2Id : memberId;
    }

    @ParameterizedTest(name = "K6: シフト希望の{0}")
    @EnumSource(LockedOp.class)
    @DisplayName("K6: 別 tx が親スケジュール行を FOR UPDATE していても、部外者は待たずに 404（ロックを取りに行かない）")
    void 部外者は親行ロックを待たずに404(LockedOp op) throws Exception {
        MockHttpServletRequestBuilder request = lockedOpRequest(op);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = holdScheduleLock(locked, release);
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
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

    @ParameterizedTest(name = "K6: シフト希望の{0}")
    @EnumSource(LockedOp.class)
    @DisplayName("K6: 許可された利用者は認可の後に FOR UPDATE へ進む（FOR UPDATE の中で塞がれていることを確認し、解放後に完了する）")
    void 許可者は認可の後に親行ロックを取りに行く(LockedOp op) throws Exception {
        MockHttpServletRequestBuilder request = lockedOpRequest(op);
        Long actor = permittedActor(op);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = holdScheduleLock(locked, release);
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            AtomicReference<Thread> requestThread = new AtomicReference<>();
            CompletableFuture<Integer> response = CompletableFuture.supplyAsync(() -> {
                requestThread.set(Thread.currentThread());
                try {
                    setAuth(actor);
                    return mockMvc.perform(request).andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }, executor);
            // 固定の待機ではなく、「要求スレッドが findByIdForUpdate の中（= 行ロック待ち）にいる」まで条件待ちする。
            // 部外者の拒否経路はこのメソッドに入らないので、ここに到達した = 認可を通過した後に FOR UPDATE へ進んだ証拠。
            Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(20))
                    .until(() -> response.isDone() || isInsideFindByIdForUpdate(requestThread.get()));
            assertThat(response.isDone()).as("ロック保持中に許可者の要求が完了してはならない").isFalse();
            assertThat(isInsideFindByIdForUpdate(requestThread.get())).isTrue();
            release.countDown();
            assertThat(response.get(30, TimeUnit.SECONDS)).isEqualTo(op.permittedStatus);
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
    @DisplayName("AC-18: 希望の削除（管理者の許可経路）— 認可のクエリ数は不変、scope 解決は 2 本、自ドメインの読み直しがちょうど 1 巡増える")
    void 希望削除_許可経路のクエリ回数() {
        Long r1 = newRequest(memberId, LocalDate.of(2026, 3, 2));
        Long r2 = newRequest(memberId, LocalDate.of(2026, 3, 3));
        Long r3 = newRequest(memberId, LocalDate.of(2026, 3, 4));
        // JIT・キャッシュの初回コストを測定から外す（同種の呼び出しを 1 回流しておく）
        gate.requireOwnerOrAdminOrConceal(adminId, teamId, "TEAM", memberId, ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND);

        long gateQueries = countStatements(() -> gate.requireOwnerOrAdminOrConceal(
                adminId, teamId, "TEAM", memberId, ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND));
        long scopeQueries = countStatements(() -> requestService.resolveRequestScope(r1));
        long bodyQueries = countStatements(() -> requestService.deleteRequest(r2));
        long facadeQueries = countStatements(() -> requestFacade.deleteRequest(r3, adminId));
        System.out.printf("[AC-18 request.delete] gate=%d scope=%d body=%d facade=%d%n",
                gateQueries, scopeQueries, bodyQueries, facadeQueries);

        // scope 解決: 希望 → 親スケジュールの 2 本。
        assertThat(scopeQueries).as("scope 解決のクエリ数").isEqualTo(2L);
        // tx 本体: 読み直し（scope 解決と同じ 2 本。親は FOR UPDATE）＋ 論理削除の UPDATE 1 本。
        // 是正前の本体（認可を除く）も同じ 3 本だったので、増えたのは scope 解決の 1 巡（= scope と同数）。
        assertThat(bodyQueries).as("tx 本体のクエリ数").isEqualTo(scopeQueries + 1);
        // 合計 = scope 解決 + 認可 + tx 本体（Facade が余計なクエリを足していない＝認可のクエリ数は Gate 単体のまま）。
        assertThat(facadeQueries).as("Facade 全体").isEqualTo(scopeQueries + gateQueries + bodyQueries);
    }

    @Test
    @DisplayName("AC-18: ポジションの更新（管理者の許可経路）— 認可のクエリ数は不変、scope 解決は 1 本、自ドメインの読み直しがちょうど 1 巡増える")
    void ポジション更新_許可経路のクエリ回数() {
        Long p1 = newPosition();
        Long p2 = newPosition();
        Long p3 = newPosition();
        UpdatePositionRequest req = new UpdatePositionRequest(null, 7, null);
        gate.requireAdminOrConceal(adminId, teamId, "TEAM", ShiftErrorCode.SHIFT_POSITION_NOT_FOUND);

        long gateQueries = countStatements(() ->
                gate.requireAdminOrConceal(adminId, teamId, "TEAM", ShiftErrorCode.SHIFT_POSITION_NOT_FOUND));
        long scopeQueries = countStatements(() -> positionService.resolvePositionScope(p1));
        long bodyQueries = countStatements(() -> positionService.updatePosition(p2, req));
        long facadeQueries = countStatements(() -> positionFacade.updatePosition(p3, req, adminId));
        System.out.printf("[AC-18 position.update] gate=%d scope=%d body=%d facade=%d%n",
                gateQueries, scopeQueries, bodyQueries, facadeQueries);

        assertThat(scopeQueries).as("scope 解決のクエリ数").isEqualTo(1L);
        assertThat(bodyQueries).as("tx 本体のクエリ数（読み直し 1 本 + UPDATE 1 本）").isEqualTo(scopeQueries + 1);
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
     * 走るため、既存 tx に合流させず {@code REQUIRES_NEW} の別接続・別 tx で書く。
     */
    private void softDeleteSchedule() {
        requiresNew(() -> em.createNativeQuery("UPDATE shift_schedules SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", scheduleId).executeUpdate());
    }

    private void hardDeletePosition(Long id) {
        requiresNew(() -> em.createNativeQuery("DELETE FROM shift_positions WHERE id = :id")
                .setParameter("id", id).executeUpdate());
    }

    private void hardDeleteRequest(Long id) {
        requiresNew(() -> em.createNativeQuery("DELETE FROM shift_requests WHERE id = :id")
                .setParameter("id", id).executeUpdate());
    }

    private void requiresNew(Runnable action) {
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.executeWithoutResult(s -> action.run());
    }

    private boolean scheduleIsSoftDeleted() {
        return tx.execute(s -> ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM shift_schedules WHERE id = :id AND deleted_at IS NOT NULL")
                .setParameter("id", scheduleId).getSingleResult()).longValue() == 1L);
    }

    private boolean requestExists(Long id) {
        return tx.execute(s -> ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM shift_requests WHERE id = :id")
                .setParameter("id", id).getSingleResult()).longValue() == 1L);
    }

    private boolean positionExists(Long id) {
        return tx.execute(s -> ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM shift_positions WHERE id = :id")
                .setParameter("id", id).getSingleResult()).longValue() == 1L);
    }

    /**
     * 親（スケジュール）配下の希望と傍観ポジションを、全列（updated_at を含む）そのまま文字列にして連結する。
     * 論理削除したスケジュール行自身・物理削除した対象ポジション・対象の希望（Victim.REQUEST）は含めない
     * （対象の希望の傍らに別メンバーの希望を 1 件置き、そちらが全列不変であることで見る）。拒否・不在で 1 列でも変われば差が出る。
     */
    private String snapshot() {
        return tx.execute(s -> Stream.of(
                        "SELECT * FROM shift_requests WHERE schedule_id = :sid AND id <> "
                                + (victimRequestId.get() == null ? -1L : victimRequestId.get()) + " ORDER BY id",
                        "SELECT * FROM shift_positions WHERE id = " + bystanderPositionId)
                .map(sql -> {
                    var query = em.createNativeQuery(sql);
                    if (sql.contains(":sid")) {
                        query.setParameter("sid", scheduleId);
                    }
                    @SuppressWarnings("unchecked")
                    List<Object> rows = query.getResultList();
                    return rows.stream()
                            .map(r -> r instanceof Object[] cols ? Arrays.deepToString(cols) : String.valueOf(r))
                            .collect(Collectors.joining(",", "[", "]"));
                })
                .collect(Collectors.joining("\n")));
    }

    /** 提出者 userId の希望を 1 件コミットして ID を返す。 */
    private Long newRequest(Long userId, LocalDate date) {
        return tx.execute(s -> requestRepository.save(ShiftRequestEntity.builder()
                .scheduleId(scheduleId)
                .userId(userId)
                .slotDate(date)
                .preference(ShiftPreference.PREFERRED)
                .note("競合テスト")
                .build()).getId());
    }

    /**
     * Victim.REQUEST 用: 提出者 member の希望（消される側）を 1 件と、傍観者 member2 の希望を 1 件コミットし、
     * 消される側の ID を返す（同時に記録する）。
     */
    private Long newVictimRequest() {
        Long id = newRequest(memberId, LocalDate.of(2026, 3, 2));
        victimRequestId.set(id);
        newRequest(member2Id, LocalDate.of(2026, 3, 4));
        return id;
    }

    /** ポジションを 1 件コミットして ID を返す（同時に「消される側」として記録する）。 */
    private Long newPosition() {
        Long id = tx.execute(s -> positionRepository.save(ShiftPositionEntity.builder()
                .teamId(teamId)
                .name("victim-" + Long.toHexString(System.nanoTime()))
                .displayOrder(1)
                .isActive(true)
                .build()).getId());
        victimPositionId.set(id);
        return id;
    }

    private MockHttpServletRequestBuilder patchRequest(Long requestId) {
        return patch("/api/v1/shifts/requests/{id}", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("preference", "AVAILABLE", "note", "更新後")));
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

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long insertUser(String email) {
        return userRepository.saveAndFlush(UserEntity.builder()
                .email(email)
                .lastName("W1FACADE").firstName("テスト").displayName("W1FACADE テスト")
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo")
                .isSearchable(true).build()).getId();
    }

    private Long insertTeam(String name) {
        return teamRepository.saveAndFlush(TeamEntity.builder()
                .name(name)
                .slug("s-" + Long.toHexString(System.nanoTime()))
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(true)
                .build()).getId();
    }
}
