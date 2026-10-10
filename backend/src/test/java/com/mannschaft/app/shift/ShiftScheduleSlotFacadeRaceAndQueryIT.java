package com.mannschaft.app.shift;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.awaitility.Awaitility;
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
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.ValueOperations;
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
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 認可ファサード型 W6a（shift の schedules・slots・remind・PDF）の<b>競合・ロック順・クエリ回数</b>の契約（試練 / red 先行）。
 *
 * <p>既存の {@code *ScopeContractIT} はテスト全体が 1 tx で、Facade と tx 本体が同じ tx に畳まれる。本クラスは
 * <b>{@code @Transactional} を付けず</b>データをコミットし、実際に「scope 解決 → 認可（tx の外）→ tx 本体」を踏ませる。
 * 終了時に自分で作った行を消す。先例: {@code ShiftTxFacadeRaceAndQueryIT}（W2）、
 * {@code RecruitmentMoneyFacadeRaceAndQueryIT}（W4）。</p>
 *
 * <ul>
 *   <li><b>K1（AC-17）</b>: 認可の<b>後</b>・tx の<b>前</b>に対象または親を論理削除する（{@code AccessControlService} の spy で、
 *       W6a の Facade から scope 付きの判定が呼ばれた直後に決定的に挿入）。対象リソースの不在コード・message の 404 になり、
 *       DB（全列＝version・更新時刻まで）が変わらない。slots の更新・割当・削除は、親だけを消しても SHIFT_002（K5）。
 *       是正前は Facade が無くフックが走らないため red。</li>
 *   <li><b>K6（AC-19・殿の判断 6）</b>: deleteSchedule・slots の書き込み 5 本・remind で、部外者は FOR UPDATE を 1 本も出さず
 *       （SQL 記録）、別 tx が行ロックを保持していても待たずに 404。remind では部外者が Valkey のロックを取らない。
 *       許可された管理者は認可の<b>後</b>に FOR UPDATE へ進む（FOR UPDATE の中で塞がれ、解放後に完了）。
 *       remind は是正前から FOR UPDATE を使わず Valkey のロックで直列化しているので、管理者の FOR UPDATE は要求しない
 *       （部外者が FOR UPDATE も Valkey のロックも取らないことだけを見る。殿の判断 2026-10-03）。</li>
 *   <li><b>AC-18</b>: 許可経路の認可クエリは是正前（同じ判定を単体で流した本数）と同じ。scope 解決（認可の前の素の読み取り）と
 *       tx 本体の読み直し（認可の後）はそれぞれ 1 本ずつ＝自ドメインの読み直しが 1 巡増える。読取の認可クエリは是正前より増えない。</li>
 * </ul>
 *
 * <p>Valkey は基底クラスで {@code StringRedisTemplate} がモックなので、{@code setIfAbsent} をメモリ上の Map で模す。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("shift W6a 認可ファサード型の競合・ロック順・クエリ回数（試練）")
class ShiftScheduleSlotFacadeRaceAndQueryIT extends AbstractMySqlIntegrationTest {

    private static final String SCHEDULES = "/api/v1/shifts/schedules";
    private static final String SLOTS = "/api/v1/shifts/slots";

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
    private FeatureFlagRepository featureFlagRepository;
    @Autowired
    private CacheManager cacheManager;

    /** 認可の「後」に割り込むため・認可の区間を測るための spy（実処理は必ず呼ぶ）。 */
    @MockitoSpyBean
    private AccessControlService accessControlService;
    /**
     * 本クラスでは振る舞いを変えないが、W2 の {@code ShiftTxFacadeRaceAndQueryIT} と同じ spy の組
     * （Gate・AccessControlService）にして ApplicationContext のキャッシュを共有する（コンテキストを増やさない）。
     */
    @MockitoSpyBean
    @SuppressWarnings("unused")
    private ScopeConcealingAccessGate gate;

    @PersistenceContext
    private EntityManager em;

    private final Map<String, String> valkey = new ConcurrentHashMap<>();

    private TransactionTemplate tx;
    private ExecutorService executor;
    private String suffix;
    private Long teamId;
    private Long adminId;
    private Long memberId;
    private Long outsiderId;
    private Long scheduleId;
    private Long collectingScheduleId;
    private Long slotId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        executor = Executors.newCachedThreadPool();
        suffix = Long.toHexString(System.nanoTime());
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, "FEATURE_SHIFT_ENABLED");
        stubValkey();

        tx.executeWithoutResult(status -> {
            // 親の行（チーム・利用者・所属・ロール・スケジュール・枠）を必ず実在させる（W4 の教訓）。
            teamId = insertTeam("W6AFACADE-" + suffix);
            adminId = insertUser("w6af-admin-" + suffix + "@example.com");
            memberId = insertUser("w6af-member-" + suffix + "@example.com");
            outsiderId = insertUser("w6af-outsider-" + suffix + "@example.com");
            MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
            MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            scheduleId = scheduleRepository.save(ShiftScheduleEntity.builder()
                    .teamId(teamId)
                    .title("W6AFACADE 公開済み " + suffix)
                    .periodType(ShiftPeriodType.WEEKLY)
                    .startDate(LocalDate.of(2026, 3, 1))
                    .endDate(LocalDate.of(2026, 3, 7))
                    .status(ShiftScheduleStatus.PUBLISHED)
                    .publishedAt(LocalDateTime.of(2026, 2, 20, 10, 0))
                    .createdBy(adminId)
                    .build()).getId();
            collectingScheduleId = scheduleRepository.save(ShiftScheduleEntity.builder()
                    .teamId(teamId)
                    .title("W6AFACADE 希望収集中 " + suffix)
                    .periodType(ShiftPeriodType.WEEKLY)
                    .startDate(LocalDate.of(2026, 4, 1))
                    .endDate(LocalDate.of(2026, 4, 7))
                    .status(ShiftScheduleStatus.COLLECTING)
                    .createdBy(adminId)
                    .build()).getId();
            slotId = slotRepository.save(ShiftSlotEntity.builder()
                    .scheduleId(scheduleId)
                    .slotDate(LocalDate.of(2026, 3, 2))
                    .startTime(LocalTime.of(9, 0))
                    .endTime(LocalTime.of(17, 0))
                    .requiredCount(2)
                    .build()).getId();
        });
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        SecurityContextHolder.clearContext();
        FeatureFlagTestSupport.clearFlagCaches(cacheManager);
        tx.executeWithoutResult(status -> {
            String schedules = "SELECT id FROM shift_schedules WHERE team_id = :tid";
            String slots = "SELECT id FROM shift_slots WHERE schedule_id IN (" + schedules + ")";
            for (String sql : List.of(
                    "DELETE FROM shift_assignments WHERE slot_id IN (SELECT x.id FROM (" + slots + ") x)",
                    "DELETE FROM shift_requests WHERE schedule_id IN (SELECT x.id FROM (" + schedules + ") x)",
                    "DELETE FROM shift_slots WHERE schedule_id IN (SELECT x.id FROM (" + schedules + ") x)",
                    "DELETE FROM shift_schedules WHERE team_id = :tid",
                    "DELETE FROM audit_logs WHERE team_id = :tid",
                    "DELETE FROM user_roles WHERE team_id = :tid",
                    "DELETE FROM memberships WHERE scope_type = 'TEAM' AND scope_id = :tid")) {
                em.createNativeQuery(sql).setParameter("tid", teamId).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM notifications WHERE user_id IN "
                            + "(SELECT x.id FROM (SELECT id FROM users WHERE email LIKE :p) x)")
                    .setParameter("p", "w6af-%-" + suffix + "@%").executeUpdate();
            em.createNativeQuery("DELETE FROM users WHERE email LIKE :p").setParameter("p", "w6af-%-" + suffix + "@%")
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM teams WHERE id = :tid").setParameter("tid", teamId).executeUpdate();
        });
    }

    @SuppressWarnings("unchecked")
    private void stubValkey() {
        valkey.clear();
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(ops);
        Mockito.when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(inv -> valkey.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
    }

    // ═════════════════════════════════════════════════════════════════════
    // K1 / AC-17: 認可の後・tx の前に対象や親を論理削除 → 対象の不在コードの 404・DB 不変
    // ═════════════════════════════════════════════════════════════════════

    /** 何を消すか。 */
    private enum Victim { SCHEDULE, COLLECTING_SCHEDULE, SLOT }

    private record Req(Long userId, MockHttpServletRequestBuilder request) { }

    private record DeletedCase(String name, Victim victim, ShiftErrorCode expected,
                               Function<ShiftScheduleSlotFacadeRaceAndQueryIT, Req> req) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<DeletedCase> deletedCases() {
        ShiftErrorCode s001 = ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND;
        ShiftErrorCode s002 = ShiftErrorCode.SHIFT_SLOT_NOT_FOUND;
        return Stream.of(
                // --- schedules（スケジュールが対象）---
                new DeletedCase("S2 詳細（一般メンバー）", Victim.SCHEDULE, s001,
                        t -> new Req(t.memberId, get(SCHEDULES + "/" + t.scheduleId))),
                new DeletedCase("S4 更新", Victim.SCHEDULE, s001,
                        t -> new Req(t.adminId, t.json(patch(SCHEDULES + "/" + t.scheduleId),
                                Map.of("title", "競合")))),
                new DeletedCase("S5 削除", Victim.SCHEDULE, s001,
                        t -> new Req(t.adminId, delete(SCHEDULES + "/" + t.scheduleId))),
                new DeletedCase("S6 状態遷移", Victim.SCHEDULE, s001,
                        t -> new Req(t.adminId, post(SCHEDULES + "/" + t.scheduleId + "/transition")
                                .param("status", "ARCHIVED"))),
                new DeletedCase("S7 サマリ", Victim.SCHEDULE, s001,
                        t -> new Req(t.adminId, get(SCHEDULES + "/" + t.scheduleId + "/summary"))),
                new DeletedCase("S8 手動リマインド", Victim.COLLECTING_SCHEDULE, s001,
                        t -> new Req(t.adminId, post(SCHEDULES + "/" + t.collectingScheduleId + "/remind"))),
                new DeletedCase("S9 複製", Victim.SCHEDULE, s001,
                        t -> new Req(t.adminId, post(SCHEDULES + "/" + t.scheduleId + "/duplicate"))),
                new DeletedCase("PDF（一般メンバー）", Victim.SCHEDULE, s001,
                        t -> new Req(t.memberId, get(SCHEDULES + "/" + t.scheduleId + "/pdf").param("layout", "team"))),
                // --- slots（親スケジュールが対象の起点）---
                new DeletedCase("L1 枠一覧（一般メンバー）", Victim.SCHEDULE, s001,
                        t -> new Req(t.memberId, get(SCHEDULES + "/" + t.scheduleId + "/slots"))),
                new DeletedCase("L2 枠作成", Victim.SCHEDULE, s001,
                        t -> new Req(t.adminId, t.json(post(SCHEDULES + "/" + t.scheduleId + "/slots"), slotBody()))),
                new DeletedCase("L3 枠一括作成", Victim.SCHEDULE, s001,
                        t -> new Req(t.adminId, t.json(post(SCHEDULES + "/" + t.scheduleId + "/slots/bulk"),
                                Map.of("slots", List.of(slotBody()))))),
                // --- slots（枠が対象。枠自身を消す）---
                new DeletedCase("L4 枠更新（枠を削除）", Victim.SLOT, s002,
                        t -> new Req(t.adminId, t.json(patch(SLOTS + "/" + t.slotId), Map.of("note", "競合")))),
                new DeletedCase("L5 割当（枠を削除）", Victim.SLOT, s002,
                        t -> new Req(t.adminId, t.json(patch(SLOTS + "/" + t.slotId + "/assignments"),
                                t.assignmentBody()))),
                new DeletedCase("L6 枠削除（枠を削除）", Victim.SLOT, s002,
                        t -> new Req(t.adminId, delete(SLOTS + "/" + t.slotId))),
                // --- slots（枠が対象。親だけ消す＝K5 でも SHIFT_002）---
                new DeletedCase("L4 枠更新（親を削除）", Victim.SCHEDULE, s002,
                        t -> new Req(t.adminId, t.json(patch(SLOTS + "/" + t.slotId), Map.of("note", "競合")))),
                new DeletedCase("L5 割当（親を削除）", Victim.SCHEDULE, s002,
                        t -> new Req(t.adminId, t.json(patch(SLOTS + "/" + t.slotId + "/assignments"),
                                t.assignmentBody()))),
                new DeletedCase("L6 枠削除（親を削除）", Victim.SCHEDULE, s002,
                        t -> new Req(t.adminId, delete(SLOTS + "/" + t.slotId)))
        );
    }

    @ParameterizedTest(name = "K1: {0}")
    @MethodSource("deletedCases")
    @DisplayName("K1: 認可の後・tx の前に対象や親が論理削除されたら、対象の不在コードの 404 で DB は変わらない")
    void 認可後に対象や親が消えたら404でDB不変(DeletedCase testCase) throws Exception {
        Req req = testCase.req().apply(this);
        String before = snapshot(testCase.victim());
        AtomicBoolean fired = installDeleteHookAfterAuthorization(testCase.victim());

        setAuth(req.userId());
        MvcResult result = mockMvc.perform(req.request()).andReturn();

        // フックが実際に走った（W6a の Facade の認可の直後に消えた）ことを先に確かめる。走らなければ 404 の根拠が崩れる。
        assertThat(fired).as("W6a の Facade から scope 付きの認可が呼ばれ、その直後に削除が挿入されたこと").isTrue();
        assertNotFound(result, testCase.expected());
        assertThat(snapshot(testCase.victim())).as("拒否後も DB は version・更新時刻まで不変").isEqualTo(before);
    }

    /**
     * scope 付きの認可（チーム ID が要る判定）が W6a の Facade の下で呼ばれた直後に、1 度だけ対象を論理削除する。
     * scope 付きの判定は scope 解決（{@code resolveScope}）の後でしか呼べないので、削除は「scope 解決の後・tx の前」に入る。
     */
    private AtomicBoolean installDeleteHookAfterAuthorization(Victim victim) {
        AtomicBoolean fired = new AtomicBoolean(false);
        Answer<Object> afterReal = inv -> {
            Object result = inv.callRealMethod();
            if (calledFromW6aFacade() && fired.compareAndSet(false, true)) {
                softDelete(victim);
            }
            return result;
        };
        Mockito.doAnswer(afterReal).when(accessControlService).isAdminOrAbove(any(), any(), any());
        Mockito.doAnswer(afterReal).when(accessControlService).isMember(any(), any(), any());
        Mockito.doAnswer(afterReal).when(accessControlService).isSupporter(any(), any(), any());
        Mockito.doAnswer(afterReal).when(accessControlService).checkAdminOrAbove(any(), any(), any());
        Mockito.doAnswer(afterReal).when(accessControlService).checkMembership(any(), any(), any());
        return fired;
    }

    private static boolean calledFromW6aFacade() {
        return Arrays.stream(Thread.currentThread().getStackTrace())
                .map(StackTraceElement::getClassName)
                .anyMatch(c -> c.equals("com.mannschaft.app.shift.service.ShiftScheduleFacade")
                        || c.equals("com.mannschaft.app.shift.service.ShiftSlotFacade")
                        || c.equals("com.mannschaft.app.shift.service.ShiftPdfFacade"));
    }

    /**
     * 対象を論理削除してコミットする。フックは Gate・ACS（readOnly の tx）の中で走りうるので、
     * {@code REQUIRES_NEW} の別 tx で書く（合流すると readOnly の tx へ UPDATE して 500 になる）。
     */
    private void softDelete(Victim victim) {
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.executeWithoutResult(s -> {
            String sql = victim == Victim.SLOT
                    ? "UPDATE shift_slots SET deleted_at = NOW() WHERE id = :id"
                    : "UPDATE shift_schedules SET deleted_at = NOW() WHERE id = :id";
            em.createNativeQuery(sql).setParameter("id", victimId(victim)).executeUpdate();
        });
    }

    private Long victimId(Victim victim) {
        return switch (victim) {
            case SCHEDULE -> scheduleId;
            case COLLECTING_SCHEDULE -> collectingScheduleId;
            case SLOT -> slotId;
        };
    }

    // ═════════════════════════════════════════════════════════════════════
    // K6 / AC-19: FOR UPDATE は認可の後（部外者は取らない・許可者は認可の後に取る）
    // ═════════════════════════════════════════════════════════════════════

    /** 殿の判断 6 の対象操作。 */
    private enum LockedOp {
        DELETE_SCHEDULE("スケジュール削除", ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND, 204, false),
        CREATE_SLOT("枠作成", ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND, 201, false),
        BULK_CREATE_SLOTS("枠一括作成", ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND, 201, false),
        UPDATE_SLOT("枠更新", ShiftErrorCode.SHIFT_SLOT_NOT_FOUND, 200, false),
        ASSIGN_SLOT("枠の割当", ShiftErrorCode.SHIFT_SLOT_NOT_FOUND, 200, false),
        DELETE_SLOT("枠削除", ShiftErrorCode.SHIFT_SLOT_NOT_FOUND, 204, false),
        REMIND("手動リマインド", ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND, 200, true);

        final String label;
        final ShiftErrorCode outsiderCode;
        final int adminStatus;
        final boolean collecting;

        LockedOp(String label, ShiftErrorCode outsiderCode, int adminStatus, boolean collecting) {
            this.label = label;
            this.outsiderCode = outsiderCode;
            this.adminStatus = adminStatus;
            this.collecting = collecting;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private MockHttpServletRequestBuilder lockedOpRequest(LockedOp op) {
        return switch (op) {
            case DELETE_SCHEDULE -> delete(SCHEDULES + "/" + scheduleId);
            case CREATE_SLOT -> json(post(SCHEDULES + "/" + scheduleId + "/slots"), slotBody());
            case BULK_CREATE_SLOTS -> json(post(SCHEDULES + "/" + scheduleId + "/slots/bulk"),
                    Map.of("slots", List.of(slotBody())));
            case UPDATE_SLOT -> json(patch(SLOTS + "/" + slotId), Map.of("note", "W6A ロック"));
            case ASSIGN_SLOT -> json(patch(SLOTS + "/" + slotId + "/assignments"), assignmentBody());
            case DELETE_SLOT -> delete(SLOTS + "/" + slotId);
            case REMIND -> post(SCHEDULES + "/" + collectingScheduleId + "/remind");
        };
    }

    private Long lockedScheduleId(LockedOp op) {
        return op.collecting ? collectingScheduleId : scheduleId;
    }

    @ParameterizedTest(name = "K6: {0}")
    @EnumSource(LockedOp.class)
    @DisplayName("K6: 部外者の要求は FOR UPDATE を 1 本も発行せず、Valkey のロックも取らない（SQL 記録・キーの有無）")
    void 部外者はFOR_UPDATEもValkeyロックも取らない(LockedOp op) throws Exception {
        MockHttpServletRequestBuilder request = lockedOpRequest(op);
        setAuth(outsiderId);
        SqlIntentCounter.reset();
        MvcResult result = mockMvc.perform(request).andReturn();
        List<String> sqls = SqlIntentCounter.capturedSqls();
        assertThat(sqls).as("SQL 記録が有効であること（StatementInspector の登録）").isNotEmpty();
        assertThat(sqls.stream().filter(ShiftScheduleSlotFacadeRaceAndQueryIT::isForUpdate).toList())
                .as("拒否経路で行ロックを取らない").isEmpty();
        assertThat(valkey).as("部外者は手動リマインドの Valkey ロックを取らない").isEmpty();
        assertNotFound(result, op.outsiderCode);
    }

    @ParameterizedTest(name = "K6: {0}")
    @EnumSource(LockedOp.class)
    @DisplayName("K6: 別 tx がスケジュール・枠の行を FOR UPDATE で握っていても、部外者は待たずに不在と同一の 404")
    void 部外者は行ロックを待たずに404(LockedOp op) throws Exception {
        MockHttpServletRequestBuilder request = lockedOpRequest(op);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = holdLocks(lockedScheduleId(op), true, locked, release);
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            // 部外者が FOR UPDATE を取りに行けば、保持中の行ロックに塞がれて 8 秒以内に返らない。
            MvcResult result = assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
                setAuth(outsiderId);
                return mockMvc.perform(request).andReturn();
            });
            assertNotFound(result, op.outsiderCode);
        } finally {
            release.countDown();
            holder.get(60, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest(name = "K6: {0}")
    // remind は是正前から FOR UPDATE を使わず Valkey のロックで直列化している（殿の判断 2026-10-03）ので対象外。
    @EnumSource(value = LockedOp.class, mode = EnumSource.Mode.EXCLUDE, names = "REMIND")
    @DisplayName("K6: 許可された管理者は認可の後に FOR UPDATE へ進む（FOR UPDATE の中で塞がれ、解放後に完了。remind は対象外）")
    void 管理者は認可の後に行ロックを取りに行く(LockedOp op) throws Exception {
        MockHttpServletRequestBuilder request = lockedOpRequest(op);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 親スケジュールの行だけを握る（tx の中で親を FOR UPDATE してたどり直す＝殿の判断 6）。
        CompletableFuture<Void> holder = holdLocks(lockedScheduleId(op), false, locked, release);
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
            // 固定の待機ではなく「要求スレッドが *ForUpdate の中（＝行ロック待ち）にいる」まで条件待ちする。
            Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(20))
                    .until(() -> response.isDone() || isInsideForUpdate(requestThread.get()));
            assertThat(response.isDone()).as("ロック保持中に管理者の要求が完了してはならない（親を FOR UPDATE で読み直す）")
                    .isFalse();
            assertThat(isInsideForUpdate(requestThread.get())).isTrue();
            release.countDown();
            assertThat(response.get(30, TimeUnit.SECONDS)).isEqualTo(op.adminStatus);
        } finally {
            release.countDown();
            holder.get(60, TimeUnit.SECONDS);
        }
    }

    /** スレッドが今、名前に {@code ForUpdate} を含むメソッド（行ロック付きの読み取り）の中にいるか。 */
    private static boolean isInsideForUpdate(Thread thread) {
        return thread != null && Arrays.stream(thread.getStackTrace())
                .anyMatch(e -> e.getMethodName().contains("ForUpdate"));
    }

    private CompletableFuture<Void> holdLocks(Long lockedScheduleId, boolean alsoSlot, CountDownLatch locked,
                                              CountDownLatch release) {
        return CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            scheduleRepository.findByIdForUpdate(lockedScheduleId).orElseThrow();
            if (alsoSlot) {
                em.createNativeQuery("SELECT id FROM shift_slots WHERE id = :id FOR UPDATE")
                        .setParameter("id", slotId).getResultList();
            }
            locked.countDown();
            try {
                release.await(40, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }), executor);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-18 / AC-19: 許可経路のクエリ（認可 / scope 解決 / tx 本体を分けて数える）
    // ═════════════════════════════════════════════════════════════════════

    /** 測る書き込み操作と、scope 解決で読むテーブル（自ドメイン）。 */
    private enum MeasuredWrite {
        UPDATE_SCHEDULE(List.of("shift_schedules"), 200),
        DELETE_SCHEDULE(List.of("shift_schedules"), 204),
        CREATE_SLOT(List.of("shift_schedules"), 201),
        UPDATE_SLOT(List.of("shift_slots", "shift_schedules"), 200),
        DELETE_SLOT(List.of("shift_slots", "shift_schedules"), 204),
        REMIND(List.of("shift_schedules"), 200);

        final List<String> scopeTables;
        final int status;

        MeasuredWrite(List<String> scopeTables, int status) {
            this.scopeTables = scopeTables;
            this.status = status;
        }
    }

    private MockHttpServletRequestBuilder measuredRequest(MeasuredWrite w) {
        return switch (w) {
            case UPDATE_SCHEDULE -> json(patch(SCHEDULES + "/" + scheduleId), Map.of("title", "W6A 計測"));
            case DELETE_SCHEDULE -> delete(SCHEDULES + "/" + scheduleId);
            case CREATE_SLOT -> json(post(SCHEDULES + "/" + scheduleId + "/slots"), slotBody());
            case UPDATE_SLOT -> json(patch(SLOTS + "/" + slotId), Map.of("note", "W6A 計測"));
            case DELETE_SLOT -> delete(SLOTS + "/" + slotId);
            case REMIND -> post(SCHEDULES + "/" + collectingScheduleId + "/remind");
        };
    }

    @ParameterizedTest(name = "AC-18: {0}")
    @EnumSource(MeasuredWrite.class)
    @DisplayName("AC-18/AC-19: 管理者の書き込み — 認可クエリは是正前と同数、認可の前は素の読み取り 1 本ずつ・FOR UPDATE 0、"
            + "認可の後に自ドメインの読み直しが 1 本ずつ（FOR UPDATE は認可の後だけ。remind はロックなしの読み直しでよい）")
    void 管理者の書き込みのクエリ回数(MeasuredWrite w) throws Exception {
        assertMeasuredWrite(w, false);
    }

    @Test
    @DisplayName("AC-18: 別スレッドの実MySQL SQLはCREATE_SLOTの認可クエリ数に含めない")
    void 別スレッドSQLを認可クエリ数に含めない() throws Exception {
        assertMeasuredWrite(MeasuredWrite.CREATE_SLOT, true);
    }

    private void assertMeasuredWrite(MeasuredWrite w, boolean injectForeignSql) throws Exception {
        long baseline = adminCheckBaseline();
        Measured m = measure(adminId, measuredRequest(w), injectForeignSql);
        if (!injectForeignSql) {
            m.print(w.name());
        }
        assertThat(m.status).isEqualTo(w.status);
        assertThat(m.authzSql).as("認可のクエリ数は是正前（isSystemAdmin＋isAdminOrAbove 単体）と同じ").isEqualTo(baseline);
        // 在籍判定は拒否経路だけ（許可経路では足さない）。
        Mockito.verify(accessControlService, never()).isMember(any(), any(), any());
        assertThat(m.beforeAuthz().stream().filter(ShiftScheduleSlotFacadeRaceAndQueryIT::isForUpdate).toList())
                .as("認可の前に FOR UPDATE を発行しない").isEmpty();
        for (String table : w.scopeTables) {
            assertThat(m.selects(m.beforeAuthz(), table)).as(table + " 認可の前の素の読み取り（scope 解決）").isEqualTo(1);
            assertThat(m.selects(m.afterAuthz(), table)).as(table + " 認可の後の読み直し（tx 本体）").isEqualTo(1);
        }
        if (w != MeasuredWrite.UPDATE_SCHEDULE && w != MeasuredWrite.REMIND) {
            // 殿の判断 6: 削除・枠の書き込みは tx の中で親スケジュールを FOR UPDATE でたどり直す。
            // remind は FOR UPDATE を要求しない（是正前から Valkey のロックで直列化。読み直しはロックなしでよい）。
            assertThat(m.afterAuthz().stream()
                    .filter(s -> isSelectFrom(s, "shift_schedules") && isForUpdate(s)).count())
                    .as("認可の後に親スケジュールを FOR UPDATE で 1 本").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("AC-18: 一般メンバーの閲覧（詳細・枠一覧）— 認可クエリは是正前（同じ判定の単体合計）を超えない")
    void 閲覧の認可クエリは増えない() throws Exception {
        // 是正前の閲覧の認可（S2: checkScheduleVisible＋checkScheduleReadAccess、L1: resolveAssignmentMasked＋
        // checkScheduleReadAccess）は isSystemAdmin・isAdminOrAbove を 2 回ずつ、isMember・isSupporter を 1 回ずつ撃つ。
        long baseline = countSql(() -> {
            accessControlService.isSystemAdmin(memberId);
            accessControlService.isAdminOrAbove(memberId, teamId, "TEAM");
            accessControlService.isSystemAdmin(memberId);
            accessControlService.isAdminOrAbove(memberId, teamId, "TEAM");
            accessControlService.isMember(memberId, teamId, "TEAM");
            accessControlService.isSupporter(memberId, teamId, "TEAM");
        });
        assertThat(baseline).as("SQL 記録が有効であること").isPositive();
        for (MockHttpServletRequestBuilder request : List.of(
                get(SCHEDULES + "/" + scheduleId), get(SCHEDULES + "/" + scheduleId + "/slots"))) {
            Measured m = measure(memberId, request);
            m.print("read");
            assertThat(m.status).isEqualTo(200);
            assertThat(m.authzSql).as("閲覧の認可クエリは是正前を超えない").isLessThanOrEqualTo(baseline);
        }
    }

    /** 是正前の管理者判定（isSystemAdmin＋isAdminOrAbove の単体）のクエリ数。初回コストを外すため 1 度流してから測る。 */
    private long adminCheckBaseline() {
        Runnable check = () -> {
            accessControlService.isSystemAdmin(adminId);
            accessControlService.isAdminOrAbove(adminId, teamId, "TEAM");
        };
        long baseline = countSql(check);
        assertThat(baseline).as("SQL 記録が有効であること").isPositive();
        Mockito.clearInvocations(accessControlService);
        return baseline;
    }

    private long countSql(Runnable action) {
        action.run();
        SqlIntentCounter.reset();
        action.run();
        long count = SqlIntentCounter.totalCount();
        Mockito.clearInvocations(accessControlService);
        return count;
    }

    /** 1 リクエストの計測結果。認可呼び出し（最外側）の区間で、SQL 記録を「前・後」に分ける。 */
    private static final class Measured {
        int status;
        List<String> sqls = List.of();
        int firstAuthzStart = -1;
        int lastAuthzEnd = -1;
        long authzSql;

        List<String> beforeAuthz() {
            return firstAuthzStart < 0 ? sqls : sqls.subList(0, Math.min(firstAuthzStart, sqls.size()));
        }

        List<String> afterAuthz() {
            return lastAuthzEnd < 0 ? List.of() : sqls.subList(Math.min(lastAuthzEnd, sqls.size()), sqls.size());
        }

        long selects(List<String> list, String table) {
            return list.stream().filter(s -> isSelectFrom(s, table)).count();
        }

        void print(String label) {
            System.out.printf("[W6a AC-18] %s status=%d authzSql=%d firstAuthzStart=%d lastAuthzEnd=%d total=%d%n%s%n",
                    label, status, authzSql, firstAuthzStart, lastAuthzEnd, sqls.size(),
                    sqls.stream().map(s -> "  " + s).collect(Collectors.joining("\n")));
        }
    }

    /**
     * 認可の呼び出し（AccessControlService の判定）を包み、その中で発行された SQL を数えながらリクエストを流す。
     * 入れ子（checkAdminOrAbove → isAdminOrAbove 等）は最外側だけを数える。
     */
    private Measured measure(Long actor, MockHttpServletRequestBuilder request) throws Exception {
        return measure(actor, request, false);
    }

    private Measured measure(Long actor, MockHttpServletRequestBuilder request, boolean injectForeignSql)
            throws Exception {
        Measured m = new Measured();
        AtomicBoolean foreignSqlInserted = new AtomicBoolean();
        ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
        Answer<Object> wrap = inv -> {
            int d = depth.get();
            depth.set(d + 1);
            int start = SqlIntentCounter.totalCount();
            if (d == 0 && m.firstAuthzStart < 0) {
                m.firstAuthzStart = start;
            }
            try {
                if (d == 0 && injectForeignSql && foreignSqlInserted.compareAndSet(false, true)) {
                    // start/end の間で別threadの実SQLを完了させる。業務Bean・認可は実物のまま。
                    CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
                        Object result = em.createNativeQuery("SELECT 1").getSingleResult();
                        assertThat(((Number) result).intValue()).as("別スレッドの実MySQL SQLが完了したこと")
                                .isEqualTo(1);
                    }), executor).get(10, TimeUnit.SECONDS);
                }
                return inv.callRealMethod();
            } finally {
                depth.set(d);
                if (d == 0) {
                    int end = SqlIntentCounter.totalCount();
                    m.authzSql += end - start;
                    m.lastAuthzEnd = end;
                }
            }
        };
        Mockito.doAnswer(wrap).when(accessControlService).isSystemAdmin(any());
        Mockito.doAnswer(wrap).when(accessControlService).isAdminOrAbove(any(), any(), any());
        Mockito.doAnswer(wrap).when(accessControlService).isMember(any(), any(), any());
        Mockito.doAnswer(wrap).when(accessControlService).isSupporter(any(), any(), any());
        Mockito.doAnswer(wrap).when(accessControlService).checkAdminOrAbove(any(), any(), any());
        Mockito.doAnswer(wrap).when(accessControlService).checkMembership(any(), any(), any());
        Mockito.clearInvocations(accessControlService);

        setAuth(actor);
        SqlIntentCounter.reset();
        MvcResult result = mockMvc.perform(request).andReturn();
        m.sqls = new ArrayList<>(SqlIntentCounter.capturedSqls());
        m.status = result.getResponse().getStatus();
        if (injectForeignSql) {
            assertThat(foreignSqlInserted).as("別スレッドSQLを測定窓へ一度挿入したこと").isTrue();
        }
        assertThat(m.sqls).as("SQL 記録が有効であること").isNotEmpty();
        assertThat(m.firstAuthzStart).as("認可の呼び出しがあること").isNotNegative();
        return m;
    }

    private static boolean isSelectFrom(String sql, String table) {
        String s = sql.toLowerCase();
        return s.stripLeading().startsWith("select")
                && (s.contains(" from " + table + " ") || s.contains(" join " + table + " "));
    }

    private static boolean isForUpdate(String sql) {
        return sql.toLowerCase().contains(" for update");
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
     * チームのスケジュール（論理削除済みを含む）と配下の枠・割当・希望を全列で連結する。
     * フックで論理削除した対象の行自身は含めない（その行は削除で変わるため）。
     */
    private String snapshot(Victim victim) {
        Long excludedSchedule = victim == Victim.SLOT ? -1L : victimId(victim);
        Long excludedSlot = victim == Victim.SLOT ? slotId : -1L;
        String schedules = "SELECT id FROM shift_schedules WHERE team_id = :tid";
        return tx.execute(s -> Stream.of(
                        "SELECT * FROM shift_schedules WHERE team_id = :tid AND id <> :xs ORDER BY id",
                        "SELECT * FROM shift_slots WHERE schedule_id IN (" + schedules + ") AND id <> :xl ORDER BY id",
                        "SELECT * FROM shift_assignments WHERE slot_id IN (SELECT id FROM shift_slots WHERE schedule_id IN ("
                                + schedules + ")) ORDER BY id",
                        "SELECT * FROM shift_requests WHERE schedule_id IN (" + schedules + ") ORDER BY id")
                .map(sql -> {
                    var query = em.createNativeQuery(sql).setParameter("tid", teamId);
                    if (sql.contains(":xs")) {
                        query.setParameter("xs", excludedSchedule);
                    }
                    if (sql.contains(":xl")) {
                        query.setParameter("xl", excludedSlot);
                    }
                    @SuppressWarnings("unchecked")
                    List<Object> rows = query.getResultList();
                    return rows.stream()
                            .map(r -> r instanceof Object[] cols ? Arrays.deepToString(cols) : String.valueOf(r))
                            .collect(Collectors.joining(",", "[", "]"));
                })
                .collect(Collectors.joining("\n")));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Object body) {
        try {
            return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> slotBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slotDate", "2026-03-03");
        body.put("startTime", "09:00:00");
        body.put("endTime", "17:00:00");
        body.put("requiredCount", 1);
        return body;
    }

    private Map<String, Object> assignmentBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("addUserIds", List.of(memberId));
        body.put("removeUserIds", List.of());
        body.put("slotVersion", 0);
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
                                + "VALUES (:email, 'W6AFACADE', 'テスト', 'W6AFACADE テスト', 'ACTIVE', "
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
