package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.budget.entity.BudgetConfigEntity;
import com.mannschaft.app.budget.repository.BudgetConfigRepository;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.ShiftPeriodType;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.event.ShiftArchivedEvent;
import com.mannschaft.app.shift.event.ShiftPublishedEvent;
import com.mannschaft.app.shift.repository.ShiftHourlyRateRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shiftbudget.batch.ShiftBudgetConsumptionReconcileBatchService;
import com.mannschaft.app.shiftbudget.dto.AllocationCreateRequest;
import com.mannschaft.app.shiftbudget.dto.RequiredSlotsRequest;
import com.mannschaft.app.shiftbudget.dto.RequiredSlotsRequest.RateMode;
import com.mannschaft.app.shiftbudget.dto.TodoBudgetLinkCreateRequest;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetAllocationService;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetCalcService;
import com.mannschaft.app.shiftbudget.service.TodoBudgetLinkService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * F01.2.1 部隊 3-C — シフト予算の複数親対応（AC-N05 予算・N10・N11・N15・G125）の実 DB 検証。
 *
 * <p>フィクスチャは「チーム T が組織 X と組織 Y の両方に ACTIVE 加盟」。単一親前提の実装
 * （{@code LIMIT 1} の任意 1 件）では、予算の組織が X か Y かが決まらず、以下が偶然にしか通らない。</p>
 * <ul>
 *   <li>AC-N05: 予算の機能フラグは、呼び出し側が明示した組織で判定される</li>
 *   <li>AC-N10: 消化は割当を持つ組織に計上される。期間の重なる別組織の割当は 409</li>
 *   <li>AC-N11: TodoBudgetLink は ACTIVE 加盟しているどちらの組織の予算にも紐づけられる</li>
 *   <li>AC-N15: 期間の重なる割当を 2 組織が並行に作ると、ちょうど一方だけが成功する</li>
 *   <li>AC-G125: 取消・照合バッチは組織を再解決せず、計上時の割当の組織を使う</li>
 * </ul>
 *
 * <p>クラスに {@code @Transactional} を付けない。消化の記録・取消は AFTER_COMMIT + 非同期で走り、
 * 並行テストは別スレッド・別トランザクションを要するため。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@TestPropertySource(properties = "feature.shift-budget.enabled=true")
@DisplayName("F01.2.1 3-C シフト予算の複数親対応（実DB）")
class ShiftBudgetMultiParentIT extends AbstractMySqlIntegrationTest {

    private static final LocalDate JUL_1 = LocalDate.of(2026, 7, 1);
    private static final LocalDate JUL_31 = LocalDate.of(2026, 7, 31);
    private static final BigDecimal AMOUNT = new BigDecimal("8000.00");

    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private ShiftBudgetCalcService calcService;
    @Autowired
    private ShiftBudgetAllocationService allocationService;
    @Autowired
    private TodoBudgetLinkService todoBudgetLinkService;
    @Autowired
    private ShiftBudgetConsumptionReconcileBatchService reconcileBatchService;
    @Autowired
    private ShiftBudgetAllocationRepository allocationRepository;
    @Autowired
    private ShiftBudgetConsumptionRepository consumptionRepository;
    @Autowired
    private ShiftScheduleRepository scheduleRepository;
    @Autowired
    private ShiftSlotRepository slotRepository;
    @Autowired
    private ShiftHourlyRateRepository hourlyRateRepository;
    @Autowired
    private BudgetConfigRepository budgetConfigRepository;
    @Autowired
    private FeatureFlagRepository featureFlagRepository;
    @Autowired
    private CacheManager cacheManager;

    @PersistenceContext
    private EntityManager em;

    private Long orgX;
    private Long orgY;
    private Long teamT;
    private Long sysAdminId;
    private Long teamAdminId;

    @BeforeEach
    void setUp() {
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, "FEATURE_SHIFT_ENABLED");
        String nonce = nonce();
        transactionTemplate.executeWithoutResult(tx -> {
            orgX = insertOrganization("3C 組織X " + nonce);
            orgY = insertOrganization("3C 組織Y " + nonce);
            teamT = insertTeam("3C チームT " + nonce);
            // 複数親: T は X と Y の両方に ACTIVE 加盟
            insertTeamOrgMembership(teamT, orgX);
            insertTeamOrgMembership(teamT, orgY);

            sysAdminId = insertUser("3c-sys-" + nonce + "@example.com");
            MembershipTestHelper.insertUserRole(em, sysAdminId, "SYSTEM_ADMIN", null, null);

            // 認可境界用: チーム T の ADMIN。組織 X / Y のメンバーではない
            teamAdminId = insertUser("3c-team-" + nonce + "@example.com");
            MembershipTestHelper.insertMembership(em, teamAdminId, ScopeType.TEAM, teamT, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, teamAdminId, "ADMIN", teamT, null);
            em.flush();
        });
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-N05: 機能フラグは URL（呼び出し側）で明示した組織で判定される
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-N05 逆算 API は指定した組織のフラグで判定される（X=有効なら通り、Y=無効なら 503 相当）")
    void 逆算は明示した組織のフラグで判定される() {
        setShiftBudgetFlag(orgY, false);
        setAuth(sysAdminId);
        RequiredSlotsRequest req = explicitRequest(teamT);

        assertThat(calcService.calculateRequiredSlots(orgX, req).requiredSlots())
                .as("X は有効。Y のフラグに引きずられて 503 になってはならない")
                .isEqualTo(62L);

        assertThatThrownBy(() -> calcService.calculateRequiredSlots(orgY, req))
                .as("Y を明示したなら Y のフラグ（OFF）で判定される")
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode().getCode()).isEqualTo("SHIFT_BUDGET_001"));
    }

    @Test
    @DisplayName("AC-N05 認可境界: チームが ACTIVE 加盟していない組織を指定した逆算は 404（存在オラクルを作らない）")
    void 加盟していない組織を指定した逆算は404() {
        Long orgZ = transactionTemplate.execute(tx -> insertOrganization("3C 組織Z " + nonce()));
        setAuth(sysAdminId);

        assertThatThrownBy(() -> calcService.calculateRequiredSlots(orgZ, explicitRequest(teamT)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode().getCode()).isEqualTo("SHIFT_BUDGET_008"));
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-N10 / AC-N15: 割当の重複 409 と並行直列化
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-N10 X が T に割当を持つ期間に、Y が重なる期間の割当を作ると 409（逐次）")
    void 別組織の重なる割当は409() {
        setAuth(sysAdminId);
        allocationService.createAllocation(orgX, allocRequest(teamT, JUL_1, JUL_31));

        assertThatThrownBy(() -> allocationService.createAllocation(orgY,
                allocRequest(teamT, LocalDate.of(2026, 7, 15), LocalDate.of(2026, 8, 15))))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode().getCode())
                                .as("HTTP 409 の割当重複コード")
                                .isEqualTo("SHIFT_BUDGET_011"));

        // 期間が重ならなければ Y も作れる（重複禁止は「重なる期間」に限る）
        allocationService.createAllocation(orgY,
                allocRequest(teamT, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)));
    }

    @Test
    @DisplayName("AC-N15 X と Y が T に期間の重なる割当を同時に作ると、ちょうど一方だけが成功し他方は 409")
    void 並行作成で一方だけが成功する() throws Exception {
        for (int round = 0; round < 3; round++) {
            LocalDate start = JUL_1.plusMonths(round * 2L);
            LocalDate end = start.plusDays(20);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<String>> futures = new ArrayList<>();
                for (Long org : List.of(orgX, orgY)) {
                    Callable<String> task = () -> {
                        setAuth(sysAdminId);
                        try {
                            ready.countDown();
                            go.await();
                            allocationService.createAllocation(org, allocRequest(teamT, start, end));
                            return "OK";
                        } catch (BusinessException e) {
                            return e.getErrorCode().getCode();
                        } finally {
                            SecurityContextHolder.clearContext();
                        }
                    };
                    futures.add(pool.submit(task));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                List<String> results = new ArrayList<>();
                for (Future<String> f : futures) {
                    results.add(f.get(60, TimeUnit.SECONDS));
                }
                assertThat(results)
                        .as("round=%d: 並行に作った 2 件は、一方だけ成功し他方は 409 でなければならない", round)
                        .containsExactlyInAnyOrder("OK", "SHIFT_BUDGET_011");
            } finally {
                pool.shutdownNow();
            }
            Integer live = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM shift_budget_allocations WHERE team_id = ? "
                            + "AND period_start = ? AND deleted_at IS NULL",
                    Integer.class, teamT, java.sql.Date.valueOf(start));
            assertThat(live).as("重なる期間の生存割当はちょうど 1 件").isEqualTo(1);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-N10: 消化は割当を持つ組織に計上される
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-N10 X だけが T に割当を持つとき、シフト公開の消化は X の割当に計上される（Y のフラグ OFF でも）")
    void 公開の消化は割当を持つ組織に計上される() {
        setShiftBudgetFlag(orgY, false);
        Fixture f = seedPublishedShift(false);
        Long allocX = seedAllocation(orgX, teamT);

        publishInTx(new ShiftPublishedEvent(f.scheduleId, teamT, sysAdminId, LocalDateTime.now()));

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(consumptionCount(allocX))
                        .as("消化が割当を持つ X に計上されること。任意の親（Y）を引いていれば 0 件のまま")
                        .isEqualTo(1));
        assertThat(consumedAmount(allocX)).isPositive();
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-G125: 取消・照合バッチは計上時の組織を使う
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-G125 アーカイブ取消は再解決せず、計上時の割当の組織 X で取り消し・監査記録する（Y のフラグ OFF でも）")
    void 取消は計上時の組織で行う() {
        setShiftBudgetFlag(orgY, false);
        Fixture f = seedPublishedShift(false);
        Long allocX = seedAllocation(orgX, teamT);
        Long consumptionId = seedConsumption(allocX, f);

        publishInTx(new ShiftArchivedEvent(f.scheduleId, teamT, sysAdminId));

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(consumptionStatus(consumptionId)).isEqualTo("CANCELLED"));
        assertThat(consumedAmount(allocX)).isEqualByComparingTo(BigDecimal.ZERO);
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(latestCancelAuditOrganizationId(f.scheduleId))
                        .as("監査ログの組織は計上時の X。再解決した任意の親ではない")
                        .isEqualTo(orgX));
    }

    @Test
    @DisplayName("AC-G125 T が X を脱退した後でも、取消は計上時の X で行われる")
    void 脱退後でも計上時の組織で取り消す() {
        Fixture f = seedPublishedShift(false);
        Long allocX = seedAllocation(orgX, teamT);
        Long consumptionId = seedConsumption(allocX, f);
        jdbcTemplate.update("DELETE FROM team_org_memberships WHERE team_id = ? AND organization_id = ?",
                teamT, orgX);

        publishInTx(new ShiftArchivedEvent(f.scheduleId, teamT, sysAdminId));

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(consumptionStatus(consumptionId)).isEqualTo("CANCELLED"));
        assertThat(latestCancelAuditOrganizationId(f.scheduleId)).isEqualTo(orgX);
    }

    @Test
    @DisplayName("AC-G125 照合バッチの監査ログ組織は消化行の割当から引いた X")
    void 照合バッチは計上時の組織を使う() {
        Fixture f = seedPublishedShift(true);
        Long allocX = seedAllocation(orgX, teamT);
        Long consumptionId = seedConsumption(allocX, f);

        int cancelled = reconcileBatchService.reconcileOrphanConsumptions();

        assertThat(cancelled).isGreaterThanOrEqualTo(1);
        assertThat(consumptionStatus(consumptionId)).isEqualTo("CANCELLED");
        assertThat(latestCancelAuditOrganizationId(f.scheduleId)).isEqualTo(orgX);
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-N11: TodoBudgetLink は ACTIVE 加盟している組織の予算に紐づけられる
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-N11 T の TODO を、ACTIVE 加盟している X・Y どちらの予算にも紐づけられる")
    void TODOはどちらの親の予算にも紐づけられる() {
        setAuth(sysAdminId);
        Long todoId = transactionTemplate.execute(tx -> insertTeamTodo());
        Long allocX = seedAllocation(orgX, null);
        Long allocY = seedAllocation(orgY, null);

        assertThat(todoBudgetLinkService.createLink(orgX,
                new TodoBudgetLinkCreateRequest(null, todoId, allocX, null, null, null)).id())
                .isNotNull();
        assertThat(todoBudgetLinkService.createLink(orgY,
                new TodoBudgetLinkCreateRequest(null, todoId, allocY, null, null, null)).id())
                .as("任意の 1 件と等値比較する実装は、片方の親への正当な紐づけを誤拒否する")
                .isNotNull();
    }

    @Test
    @DisplayName("AC-N11 認可境界: 加盟していない組織 Z の予算には T の TODO を紐づけられない（404）")
    void 加盟していない組織の予算には紐づけられない() {
        setAuth(sysAdminId);
        Long orgZ = transactionTemplate.execute(tx -> insertOrganization("3C 組織Z " + nonce()));
        Long todoId = transactionTemplate.execute(tx -> insertTeamTodo());
        Long allocZ = seedAllocation(orgZ, null);

        assertThatThrownBy(() -> todoBudgetLinkService.createLink(orgZ,
                new TodoBudgetLinkCreateRequest(null, todoId, allocZ, null, null, null)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode().getCode()).isEqualTo("SHIFT_BUDGET_025"));
    }

    // ═══════════════════════════════════════════════════════════════
    // 認可境界: 他組織の予算に触れない
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("認可境界 組織 X の割当は、組織 Y を指定しても読めない（404）")
    void 他組織の割当は読めない() {
        setAuth(sysAdminId);
        Long allocX = seedAllocation(orgX, teamT);

        assertThatThrownBy(() -> allocationService.getAllocation(orgY, allocX))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode().getCode()).isEqualTo("SHIFT_BUDGET_010"));
    }

    @Test
    @DisplayName("認可境界 組織メンバーでないチーム管理者は、X の割当を作れない（BUDGET_ADMIN 要求）")
    void 組織権限の無いユーザーは割当を作れない() {
        setAuth(teamAdminId);

        assertThatThrownBy(() -> allocationService.createAllocation(orgX, allocRequest(teamT, JUL_1, JUL_31)))
                .isInstanceOf(BusinessException.class);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shift_budget_allocations WHERE team_id = ?", Integer.class, teamT);
        assertThat(count).isZero();
    }

    // ═══════════════════════════════════════════════════════════════
    // フィクスチャ / ヘルパー
    // ═══════════════════════════════════════════════════════════════

    private record Fixture(Long scheduleId, Long slotId) {
    }

    private Fixture seedPublishedShift(boolean archived) {
        return transactionTemplate.execute(tx -> {
            ShiftScheduleEntity schedule = scheduleRepository.save(ShiftScheduleEntity.builder()
                    .teamId(teamT)
                    .title("3C シフト " + nonce())
                    .periodType(ShiftPeriodType.WEEKLY)
                    .startDate(JUL_1)
                    .endDate(LocalDate.of(2026, 7, 7))
                    .status(archived ? ShiftScheduleStatus.ARCHIVED : ShiftScheduleStatus.PUBLISHED)
                    .publishedAt(LocalDateTime.of(2026, 6, 20, 10, 0))
                    .publishedBy(sysAdminId)
                    .createdBy(sysAdminId)
                    .build());
            ShiftSlotEntity slot = slotRepository.save(ShiftSlotEntity.builder()
                    .scheduleId(schedule.getId())
                    .slotDate(JUL_1)
                    .startTime(LocalTime.of(9, 0))
                    .endTime(LocalTime.of(17, 0))
                    .requiredCount(1)
                    .assignedUserIds("[" + sysAdminId + "]")
                    .build());
            hourlyRateRepository.save(ShiftHourlyRateEntity.builder()
                    .userId(sysAdminId)
                    .teamId(teamT)
                    .hourlyRate(new BigDecimal("1000.00"))
                    .effectiveFrom(LocalDate.of(2026, 1, 1))
                    .build());
            return new Fixture(schedule.getId(), slot.getId());
        });
    }

    /** 割当を直接投入する（重複判定を通さない。フィクスチャ用）。teamId=null は組織全体割当。 */
    private Long seedAllocation(Long org, Long team) {
        return transactionTemplate.execute(tx -> allocationRepository.save(
                ShiftBudgetAllocationEntity.builder()
                        .organizationId(org)
                        .teamId(team)
                        .fiscalYearId(9101L)
                        .budgetCategoryId(9102L)
                        .periodStart(JUL_1)
                        .periodEnd(JUL_31)
                        .allocatedAmount(new BigDecimal("300000.00"))
                        .consumedAmount(BigDecimal.ZERO)
                        .confirmedAmount(BigDecimal.ZERO)
                        .currency("JPY")
                        .createdBy(sysAdminId)
                        .version(0L)
                        .build()).getId());
    }

    private Long seedConsumption(Long allocationId, Fixture f) {
        return transactionTemplate.execute(tx -> {
            ShiftBudgetConsumptionEntity c = consumptionRepository.save(
                    ShiftBudgetConsumptionEntity.builder()
                            .allocationId(allocationId)
                            .shiftId(f.scheduleId)
                            .slotId(f.slotId)
                            .userId(sysAdminId)
                            .hourlyRateSnapshot(new BigDecimal("1000.00"))
                            .hours(new BigDecimal("8.00"))
                            .amount(AMOUNT)
                            .currency("JPY")
                            .status(ShiftBudgetConsumptionStatus.PLANNED)
                            .recordedAt(LocalDateTime.now())
                            .build());
            allocationRepository.incrementConsumedAmount(allocationId, AMOUNT);
            return c.getId();
        });
    }

    private void publishInTx(Object event) {
        transactionTemplate.executeWithoutResult(tx -> eventPublisher.publishEvent(event));
    }

    private void setShiftBudgetFlag(Long org, boolean enabled) {
        transactionTemplate.executeWithoutResult(tx -> budgetConfigRepository.save(
                BudgetConfigEntity.builder()
                        .scopeType("ORGANIZATION")
                        .scopeId(org)
                        .shiftBudgetEnabled(enabled)
                        .build()));
    }

    private static RequiredSlotsRequest explicitRequest(Long team) {
        return new RequiredSlotsRequest(team, new BigDecimal("300000"), new BigDecimal("4.0"),
                RateMode.EXPLICIT, new BigDecimal("1200"), null);
    }

    private static AllocationCreateRequest allocRequest(Long team, LocalDate start, LocalDate end) {
        return new AllocationCreateRequest(team, null, 9101L, 9102L, start, end,
                new BigDecimal("300000"), "JPY", null);
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private int consumptionCount(Long allocationId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shift_budget_consumptions WHERE allocation_id = ?",
                Integer.class, allocationId);
    }

    private String consumptionStatus(Long consumptionId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM shift_budget_consumptions WHERE id = ?", String.class, consumptionId);
    }

    private BigDecimal consumedAmount(Long allocationId) {
        return jdbcTemplate.queryForObject(
                "SELECT consumed_amount FROM shift_budget_allocations WHERE id = ?",
                BigDecimal.class, allocationId);
    }

    /** 当該シフトの消化取消の監査ログ（最新 1 件）の organization_id。まだ無ければ null（await がリトライ）。 */
    private Long latestCancelAuditOrganizationId(Long scheduleId) {
        List<Long> rows = jdbcTemplate.queryForList(
                "SELECT organization_id FROM audit_logs "
                        + "WHERE event_type = 'SHIFT_BUDGET_CONSUMPTION_CANCELLED' AND team_id = ? "
                        + "ORDER BY id DESC LIMIT 1",
                Long.class, teamT);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Long insertTeamTodo() {
        em.createNativeQuery(
                        "INSERT INTO todos (scope_type, scope_id, milestone_locked, position, depth, "
                                + "title, status, priority, progress_rate, progress_manual, created_by, "
                                + "sort_order, created_at, updated_at) "
                                + "VALUES ('TEAM', :teamId, 0, 0, 0, '3C TODO', 'OPEN', 'MEDIUM', "
                                + "0, 0, :userId, 0, NOW(), NOW())")
                .setParameter("teamId", teamT)
                .setParameter("userId", sysAdminId)
                .executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM todos WHERE scope_id = :teamId AND title = '3C TODO' "
                                + "ORDER BY id DESC LIMIT 1")
                .setParameter("teamId", teamT)
                .getSingleResult()).longValue();
    }

    private static String nonce() {
        return String.valueOf(System.nanoTime());
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
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
        em.flush();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void insertTeamOrgMembership(Long team, Long org) {
        em.createNativeQuery(
                        "INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, created_at) "
                                + "VALUES (:tid, :oid, 'ACTIVE', NOW(), NOW())")
                .setParameter("tid", team)
                .setParameter("oid", org)
                .executeUpdate();
        em.flush();
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
                                + "VALUES (:email, '3C', 'テスト', '3C ユーザー', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }
}
