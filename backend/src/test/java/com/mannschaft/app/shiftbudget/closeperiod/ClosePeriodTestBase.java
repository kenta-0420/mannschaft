package com.mannschaft.app.shiftbudget.closeperiod;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.budget.service.ShiftBudgetMonthlyJournalService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.shift.ShiftPeriodType;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.event.ShiftArchivedEvent;
import com.mannschaft.app.shift.event.ShiftPublishedEvent;
import com.mannschaft.app.shift.event.ShiftScheduleCloseReason;
import com.mannschaft.app.shift.event.ShiftScheduleClosedEvent;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.listener.ShiftBudgetConsumptionCancelListener;
import com.mannschaft.app.shiftbudget.listener.ShiftBudgetConsumptionRecordListener;
import com.mannschaft.app.shiftbudget.service.MonthlyShiftBudgetCloseCoordinator;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetBusinessDateProvider;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetCloseStageService;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetLockStepHooks;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetRecordDeferralService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-260909-2152 PR-α（組織の締め日と締め期間）試練の共通フィクスチャ（実DB・実HTTP）。
 *
 * <h2>起動構成（派生クラスで変えないこと。Spring TestContext を1つに保つ）</h2>
 * <p>{@link AbstractMySqlIntegrationTest}（Testcontainers MySQL）＋ {@code @AutoConfigureMockMvc}。
 * 途中失敗・順序固定のため、3段締めの各段（{@link ShiftBudgetCloseStageService}・{@link ShiftBudgetMonthlyJournalService}）、
 * 試験用フック {@link ShiftBudgetLockStepHooks}、業務日 {@link ShiftBudgetBusinessDateProvider} を spy にする。
 * 業務日は既定で {@link #DEFAULT_TODAY} に固定する（試験が {@link #today(LocalDate)} で変える）。</p>
 *
 * <h2>勤務の作り方</h2>
 * <p>消化行は直接 INSERT せず、公開済みシフト＋枠を作って実リスナー {@code onShiftPublished} を呼び、実経路で記録させる
 * （work_date の正本は勤務枠の slot_date。現行は非同期のため event-pool の空きを待つ。α 実装後は同期化され即時に戻る）。</p>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>締めは段ごとの独立 TX のコミットを観測する。段取り役は TX 内から呼ぶと拒否される（AC69）。</p>
 *
 * <h2>試練が固定する API 契約（FE 試練108 と共有）</h2>
 * <ul>
 *   <li>{@code GET /api/v1/shift-budget/closing-settings}（X-Organization-Id）→ {@code data}:
 *       {closing_day, revision, min_effective_period_key, changes:[{revision, effective_period_key, closing_day,
 *       previous_closing_day, reason, actor_user_id, created_at}]}。closing_day=0 は末日。
 *       min_effective_period_key は「今日を含む期間のキー」と「最後の締め（旧締め含む）の次の期間キー」の遅い方</li>
 *   <li>{@code POST /api/v1/shift-budget/closing-settings/changes}（X-Organization-Id）要求
 *       {expected_revision, closing_day, effective_period_key, reason}</li>
 *   <li>{@code GET /api/v1/shift-budget/close-periods?from=YYYY-MM&to=YYYY-MM}（X-Organization-Id）→
 *       {@code data.items:[{period_key, period_start, period_end, closing_day}]}</li>
 *   <li>{@code GET /api/v1/shift-budget/allocations/{id}/close-periods}（X-Organization-Id）→ 同形の items
 *       （割当と重なる期間）</li>
 *   <li>手動締め {@code POST /api/v1/shift-budget/monthly-close} {organization_id, year_month=期間キー}。応答 data に
 *       period_start / period_end / closing_day を追加</li>
 *   <li>未計上勤務 {@code GET /api/v1/shift-budget/allocations/{id}/monthly-closes/{期間キー}/uncounted-shifts} →
 *       {@code data.items[]}（slot_id, user_id, work_date …）</li>
 * </ul>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
abstract class ClosePeriodTestBase extends AbstractMySqlIntegrationTest {

    protected static final LocalDate DEFAULT_TODAY = LocalDate.of(2026, 10, 1);
    protected static final String SOURCE_MONTHLY = "SHIFT_BUDGET_MONTHLY";
    protected static final String RATE = "1000";
    /** 9:00〜17:00＝8時間×1000円。 */
    protected static final BigDecimal DAY_AMOUNT = new BigDecimal("8000");

    private static final AtomicLong USER_SEQ = new AtomicLong(2_152_500_000L);

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected TransactionTemplate tx;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected FeatureFlagRepository featureFlags;
    @Autowired protected CacheManager caches;
    @Autowired protected ShiftBudgetConsumptionRecordListener recordListener;
    @Autowired protected ShiftBudgetConsumptionCancelListener cancelListener;
    @Autowired protected MonthlyShiftBudgetCloseCoordinator coordinator;
    @Autowired protected ShiftBudgetRecordDeferralService deferralService;
    @Autowired @Qualifier("event-pool") protected Executor eventPool;
    @MockitoSpyBean protected ShiftBudgetCloseStageService stage;
    @MockitoSpyBean protected ShiftBudgetMonthlyJournalService journal;
    @MockitoSpyBean protected ShiftBudgetLockStepHooks hooks;
    @MockitoSpyBean protected ShiftBudgetBusinessDateProvider dateProvider;
    @PersistenceContext protected EntityManager em;

    protected Long orgId;
    protected Long teamId;
    /** 組織 BUDGET_VIEW + BUDGET_ADMIN。 */
    protected Long adminId;
    /** 組織 BUDGET_VIEW のみ。 */
    protected Long viewerId;
    protected Long workerId;
    protected Long fiscalYearId;
    protected Long categoryId;

    @BeforeEach
    void setUpClosePeriodFixture() {
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        FeatureFlagTestSupport.enable(featureFlags, caches, "FEATURE_SHIFT_ENABLED");
        today(DEFAULT_TODAY);
        tx.executeWithoutResult(s -> {
            orgId = insertOrganization();
            teamId = insertTeam();
            insertTeamOrgMembership(teamId, orgId);
            em.flush();
        });
        adminId = newUser();
        grantBudget(adminId, orgId, true);
        tx.executeWithoutResult(s -> {
            fiscalYearId = insertFiscalYear(orgId, "2026-04-01", "2027-03-31", "OPEN");
            categoryId = insertCategory(fiscalYearId);
        });
        viewerId = newUser();
        grantBudget(viewerId, orgId, false);
        workerId = worker(teamId, orgId);
    }

    @AfterEach
    void tearDownClosePeriodFixture() {
        SecurityContextHolder.clearContext();
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        Mockito.reset(stage, journal, hooks, dateProvider);
    }

    // ───────────────────────── 業務日 ─────────────────────────

    protected void today(LocalDate date) {
        doReturn(date).when(dateProvider).today();
    }

    // ───────────────────────── 組織・人 ─────────────────────────

    protected Long newUser() {
        Long id = USER_SEQ.incrementAndGet();
        tx.executeWithoutResult(s -> MembershipTestHelper.insertActiveUser(em, id));
        return id;
    }

    /** 組織・チームのメンバーで、時給 1000 円（2025-01-01 から）を持つ勤務者。 */
    protected Long worker(Long team, Long org) {
        Long id = newUser();
        tx.executeWithoutResult(s -> {
            MembershipTestHelper.insertMembership(em, id, ScopeType.ORGANIZATION, org, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, id, ScopeType.TEAM, team, RoleKind.MEMBER);
            em.persist(ShiftHourlyRateEntity.builder().userId(id).teamId(team).hourlyRate(new BigDecimal(RATE))
                    .effectiveFrom(LocalDate.of(2025, 1, 1)).build());
        });
        return id;
    }

    /** 組織所属 + DEPUTY_ADMIN + 権限グループ（BUDGET_VIEW、admin なら BUDGET_ADMIN も）。 */
    protected void grantBudget(Long userId, Long scopeOrgId, boolean budgetAdmin) {
        tx.executeWithoutResult(s -> {
            MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, scopeOrgId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, userId, "DEPUTY_ADMIN", null, scopeOrgId);
            PermissionGroupEntity group = PermissionGroupEntity.builder().organizationId(scopeOrgId)
                    .targetRole(PermissionGroupEntity.TargetRole.DEPUTY_ADMIN).name("2152α担当")
                    .createdBy(userId).build();
            em.persist(group);
            em.flush();
            for (String permission : budgetAdmin ? List.of("BUDGET_VIEW", "BUDGET_ADMIN") : List.of("BUDGET_VIEW")) {
                em.createNativeQuery("INSERT INTO permissions (name, display_name, scope, created_at, updated_at) "
                                + "SELECT :p, :p, 'ORGANIZATION', NOW(), NOW() FROM DUAL "
                                + "WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = :p)")
                        .setParameter("p", permission).executeUpdate();
                Long permissionId = ((Number) em.createNativeQuery("SELECT id FROM permissions WHERE name = :p")
                        .setParameter("p", permission).getSingleResult()).longValue();
                em.persist(PermissionGroupPermissionEntity.builder().groupId(group.getId())
                        .permissionId(permissionId).build());
            }
            em.persist(UserPermissionGroupEntity.builder().userId(userId).groupId(group.getId())
                    .assignedBy(userId).build());
            em.flush();
        });
    }

    protected void grantSystemAdmin(Long userId) {
        tx.executeWithoutResult(s -> MembershipTestHelper.insertUserRole(em, userId, "SYSTEM_ADMIN", null, null));
    }

    /** 別組織（チーム・年度・費目・予算担当者つき）。戻り値 {orgId, teamId, adminId, fiscalYearId, categoryId}。 */
    protected long[] otherOrganization() {
        long[] ids = new long[5];
        tx.executeWithoutResult(s -> {
            ids[0] = insertOrganization();
            ids[1] = insertTeam();
            insertTeamOrgMembership(ids[1], ids[0]);
            ids[3] = insertFiscalYear(ids[0], "2026-04-01", "2027-03-31", "OPEN");
            ids[4] = insertCategory(ids[3]);
        });
        Long admin = newUser();
        grantBudget(admin, ids[0], true);
        ids[2] = admin;
        return ids;
    }

    protected Long insertOrganization() {
        OrganizationEntity organization = OrganizationEntity.builder()
                .name("2152締め期間")
                .slug("p-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8))
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true)
                .lifecycleStatus(OrganizationEntity.LifecycleStatus.ACTIVE)
                .build();
        em.persist(organization);
        em.flush();
        return organization.getId();
    }

    protected Long insertTeam() {
        String name = "2152αチーム" + UUID.randomUUID();
        em.createNativeQuery("INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                        + "created_at, updated_at) VALUES (:name, 'PUBLIC', 1, 0, 0, "
                        + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name).executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    /** 同じ組織に加盟する別チーム（同一日に複数割当を持たせる試験用。割当はチーム単位で解決される）。 */
    protected Long newTeamInOrg() {
        Long[] id = new Long[1];
        tx.executeWithoutResult(s -> {
            id[0] = insertTeam();
            insertTeamOrgMembership(id[0], orgId);
        });
        return id[0];
    }

    protected void insertTeamOrgMembership(Long team, Long org) {
        em.createNativeQuery("INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, created_at) "
                        + "VALUES (:tid, :oid, 'ACTIVE', NOW(), NOW())")
                .setParameter("tid", team).setParameter("oid", org).executeUpdate();
        em.flush();
    }

    protected Long insertFiscalYear(Long org, String start, String end, String status) {
        String name = "2152α年度" + UUID.randomUUID();
        em.createNativeQuery("INSERT INTO budget_fiscal_years (scope_type, scope_id, name, start_date, end_date, "
                        + "status, created_by, version, created_at, updated_at) VALUES ('ORGANIZATION', :org, :name, "
                        + ":s, :e, :st, :uid, 0, NOW(), NOW())")
                .setParameter("uid", adminId == null ? 1L : adminId)
                .setParameter("org", org).setParameter("name", name).setParameter("s", start)
                .setParameter("e", end).setParameter("st", status).executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT id FROM budget_fiscal_years WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    protected Long insertCategory(Long fy) {
        String name = "2152α費目" + UUID.randomUUID();
        em.createNativeQuery("INSERT INTO budget_categories (fiscal_year_id, name, category_type, sort_order, "
                        + "version, created_at, updated_at) VALUES (:fy, :name, 'EXPENSE', 0, 0, NOW(), NOW())")
                .setParameter("fy", fy).setParameter("name", name).executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT id FROM budget_categories WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    protected void setFiscalYearStatus(Long fy, String status) {
        jdbc.update("UPDATE budget_fiscal_years SET status = ? WHERE id = ?", status, fy);
    }

    // ───────────────────────── 割当・勤務 ─────────────────────────

    protected Long allocation(LocalDate start, LocalDate end) {
        return allocation(orgId, teamId, fiscalYearId, categoryId, start, end);
    }

    protected Long allocation(Long org, Long team, Long fy, Long category, LocalDate start, LocalDate end) {
        return tx.execute(s -> {
            ShiftBudgetAllocationEntity a = ShiftBudgetAllocationEntity.builder()
                    .organizationId(org).teamId(team).fiscalYearId(fy).budgetCategoryId(category)
                    .periodStart(start).periodEnd(end)
                    .allocatedAmount(new BigDecimal("10000000")).consumedAmount(BigDecimal.ZERO)
                    .confirmedAmount(BigDecimal.ZERO).currency("JPY").createdBy(adminId).version(0L).build();
            em.persist(a);
            em.flush();
            return a.getId();
        });
    }

    /** 公開済みシフト（1枠・9:00〜17:00）。戻り値 {scheduleId, slotId}。記録はしない（{@link #publish} で記録）。 */
    protected long[] shift(LocalDate date, Long... userIds) {
        return shift(teamId, date, LocalTime.of(9, 0), LocalTime.of(17, 0), userIds);
    }

    protected long[] shift(Long team, LocalDate date, LocalTime start, LocalTime end, Long... userIds) {
        return tx.execute(s -> {
            ShiftScheduleEntity schedule = ShiftScheduleEntity.builder().teamId(team).title("2152αシフト")
                    .periodType(ShiftPeriodType.WEEKLY).startDate(date).endDate(date.plusDays(6))
                    .status(ShiftScheduleStatus.PUBLISHED).publishedAt(LocalDateTime.of(2026, 3, 20, 10, 0))
                    .publishedBy(adminId).createdBy(adminId).build();
            em.persist(schedule);
            em.flush();
            ShiftSlotEntity slot = ShiftSlotEntity.builder().scheduleId(schedule.getId()).slotDate(date)
                    .startTime(start).endTime(end).requiredCount(userIds.length)
                    .assignedUserIds(json(userIds)).build();
            em.persist(slot);
            em.flush();
            return new long[] {schedule.getId(), slot.getId()};
        });
    }

    /** 公開済みシフトを作り、実リスナーで記録する。 */
    protected long[] worked(LocalDate date, Long... userIds) {
        long[] ids = shift(date, userIds);
        publish(ids[0]);
        return ids;
    }

    protected long[] worked(Long team, LocalDate date, Long... userIds) {
        long[] ids = shift(team, date, LocalTime.of(9, 0), LocalTime.of(17, 0), userIds);
        publish(ids[0], team);
        return ids;
    }

    protected void publish(long scheduleId) {
        publish(scheduleId, teamId);
    }

    /** 再公開（公開イベントを実リスナーへ渡し、記録の完了を待つ）。 */
    protected void publish(long scheduleId, Long team) {
        jdbc.update("UPDATE shift_schedules SET status = 'PUBLISHED' WHERE id = ?", scheduleId);
        recordListener.onShiftPublished(new ShiftPublishedEvent(scheduleId, team, adminId, LocalDateTime.now()));
        drainEventPool();
    }

    /** 公開取消（PUBLISHED → COLLECTING）と取消リスナー。 */
    protected void unpublish(long scheduleId) {
        jdbc.update("UPDATE shift_schedules SET status = 'COLLECTING' WHERE id = ?", scheduleId);
        cancelListener.onShiftScheduleClosed(new ShiftScheduleClosedEvent(scheduleId, teamId, adminId,
                ShiftScheduleCloseReason.UNPUBLISHED));
        drainEventPool();
    }

    /** アーカイブと取消リスナー。 */
    protected void archive(long scheduleId) {
        jdbc.update("UPDATE shift_schedules SET status = 'ARCHIVED' WHERE id = ?", scheduleId);
        cancelListener.onShiftArchived(new ShiftArchivedEvent(scheduleId, teamId, adminId));
        drainEventPool();
    }

    /** 勤務枠の日付だけを変える（ShiftSlotService の日付変更に相当。再公開はしない）。 */
    protected void moveSlot(long slotId, LocalDate newDate) {
        jdbc.update("UPDATE shift_slots SET slot_date = ? WHERE id = ?", newDate, slotId);
    }

    protected void assign(long slotId, Long... userIds) {
        jdbc.update("UPDATE shift_slots SET assigned_user_ids = ?, required_count = ? WHERE id = ?",
                json(userIds), userIds.length, slotId);
    }

    private static String json(Long... userIds) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < userIds.length; i++) {
            b.append(i == 0 ? "" : ",").append(userIds[i]);
        }
        return b.append(']').toString();
    }

    /** event-pool が空（実行中0・待ち行列0）の状態が連続5回続くまで待つ。 */
    protected void drainEventPool() {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) eventPool;
        AtomicInteger idleStreak = new AtomicInteger();
        await().atMost(30, TimeUnit.SECONDS).pollInterval(50, TimeUnit.MILLISECONDS).until(() -> {
            boolean idle = pool.getActiveCount() == 0 && pool.getThreadPoolExecutor().getQueue().isEmpty();
            if (!idle) {
                idleStreak.set(0);
                return false;
            }
            return idleStreak.incrementAndGet() >= 5;
        });
    }

    // ───────────────────────── HTTP ─────────────────────────

    protected MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, Long actorId, Long headerOrg) {
        return builder.header("X-Organization-Id", headerOrg)
                .with(authentication(new UsernamePasswordAuthenticationToken(actorId.toString(), null, List.of())));
    }

    /** 手動締め（期間キー）。 */
    protected ResultActions close(Long actorId, Long org, String periodKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organization_id", org);
        body.put("year_month", periodKey);
        return mvc.perform(as(post("/api/v1/shift-budget/monthly-close"), actorId, org)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    protected ResultActions close(String periodKey) throws Exception {
        return close(adminId, orgId, periodKey);
    }

    protected ResultActions getSettings(Long actorId, Long headerOrg) throws Exception {
        return mvc.perform(as(get("/api/v1/shift-budget/closing-settings"), actorId, headerOrg));
    }

    protected ResultActions changeClosingDay(Long actorId, Long headerOrg, Object expectedRevision, Object closingDay,
                                             String effectiveKey, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expected_revision", expectedRevision);
        body.put("closing_day", closingDay);
        body.put("effective_period_key", effectiveKey);
        body.put("reason", reason);
        return mvc.perform(as(post("/api/v1/shift-budget/closing-settings/changes"), actorId, headerOrg)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    /** 予算担当者が締め日を変更する（現在の revision を読んで送る）。 */
    protected void setClosingDay(int closingDay, String effectiveKey) throws Exception {
        long revision = data(getSettings(adminId, orgId)).path("revision").asLong();
        changeClosingDay(adminId, orgId, revision, closingDay, effectiveKey, "2152α 締め日の設定")
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is2xxSuccessful());
    }

    protected ResultActions closePeriods(Long actorId, Long headerOrg, String from, String to) throws Exception {
        return mvc.perform(as(get("/api/v1/shift-budget/close-periods").param("from", from).param("to", to),
                actorId, headerOrg));
    }

    protected ResultActions allocationClosePeriods(Long actorId, Long headerOrg, Long allocationId) throws Exception {
        return mvc.perform(as(get("/api/v1/shift-budget/allocations/" + allocationId + "/close-periods"),
                actorId, headerOrg));
    }

    protected ResultActions uncounted(Long actorId, Long allocationId, String periodKey) throws Exception {
        return mvc.perform(as(get("/api/v1/shift-budget/allocations/" + allocationId + "/monthly-closes/"
                + periodKey + "/uncounted-shifts"), actorId, orgId));
    }

    protected JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    // ───────────────────────── DB 観測 ─────────────────────────

    /** 消化行（slot_id, user_id, status, amount, cancel_reason, deleted_at）。work_date 列には依存しない。 */
    protected List<Map<String, Object>> consumptions(Long allocationId) {
        return jdbc.queryForList("SELECT c.id, c.slot_id, c.user_id, c.status, c.amount, c.cancel_reason, c.deleted_at "
                + "FROM shift_budget_consumptions c WHERE c.allocation_id = ? ORDER BY c.id", allocationId);
    }

    /** 枠の生存消化行の状態（無ければ null）。 */
    protected String liveStatus(long slotId, Long userId) {
        List<String> s = jdbc.queryForList("SELECT status FROM shift_budget_consumptions WHERE slot_id = ? AND user_id = ? "
                + "AND deleted_at IS NULL AND status IN ('PLANNED','CONFIRMED')", String.class, slotId, userId);
        return s.isEmpty() ? null : String.join(",", s);
    }

    protected long countStatus(long slotId, Long userId, String status) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM shift_budget_consumptions WHERE slot_id = ? AND user_id = ? "
                + "AND status = ? AND deleted_at IS NULL", Long.class, slotId, userId, status);
        return n == null ? 0 : n;
    }

    protected Map<String, Object> allocationCache(Long allocationId) {
        return jdbc.queryForMap("SELECT consumed_amount, confirmed_amount FROM shift_budget_allocations WHERE id = ?",
                allocationId);
    }

    protected BigDecimal confirmedAmount(Long allocationId) {
        return (BigDecimal) allocationCache(allocationId).get("confirmed_amount");
    }

    protected BigDecimal consumedAmount(Long allocationId) {
        return (BigDecimal) allocationCache(allocationId).get("consumed_amount");
    }

    /** 月次（期間）仕訳。生存のみ。 */
    protected List<Map<String, Object>> journals(Long allocationId) {
        return jdbc.queryForList("SELECT id, amount, transaction_date, fiscal_year_id, title FROM budget_transactions "
                + "WHERE source_type = ? AND source_id = ? AND deleted_at IS NULL ORDER BY transaction_date, id",
                SOURCE_MONTHLY, allocationId);
    }

    /** 締めイベント（(c)）。 */
    protected List<Map<String, Object>> closeEvents(Long allocationId) {
        return jdbc.queryForList("SELECT e.id, e.target_month, e.state, e.journal_date, e.line_count, e.planned_total, "
                + "e.journal_transaction_id, e.actor_kind FROM shift_budget_monthly_close_events e "
                + "WHERE e.allocation_id = ? ORDER BY e.prepared_at, e.target_month", allocationId);
    }

    protected List<Map<String, Object>> closedEvents(Long allocationId) {
        return closeEvents(allocationId).stream().filter(e -> "CLOSED".equals(e.get("state"))).toList();
    }

    /** 確定済み締め期間行（組織）。 */
    protected List<Map<String, Object>> fixedPeriods(Long org) {
        return jdbc.queryForList("SELECT period_key, period_start, period_end, closing_day FROM shift_budget_close_periods "
                + "WHERE organization_id = ? ORDER BY period_key", org);
    }

    /** 投稿判定行（budget_shift_close_postings）。 */
    protected List<Map<String, Object>> postings(Long allocationId) {
        return jdbc.queryForList("SELECT close_event_id, outcome, journal_transaction_id, reason "
                + "FROM budget_shift_close_postings WHERE allocation_id = ? ORDER BY decided_at", allocationId);
    }

    /** 消化行に締めイベントの印がある件数。 */
    protected long markedCount(Long allocationId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM shift_budget_consumptions WHERE allocation_id = ? "
                + "AND close_event_id IS NOT NULL", Long.class, allocationId);
        return n == null ? 0 : n;
    }

    /** 遅延記録（シフト単位）。 */
    protected List<Map<String, Object>> deferrals(long shiftId) {
        return jdbc.queryForList("SELECT id, state, attempt_count, last_error, claimed_by, lease_until "
                + "FROM shift_budget_record_deferrals WHERE shift_id = ? ORDER BY created_at", shiftId);
    }

    protected UUID pendingDeferralId(long shiftId) {
        byte[] raw = jdbc.queryForObject("SELECT id FROM shift_budget_record_deferrals WHERE shift_id = ? "
                + "AND state = 'PENDING'", byte[].class, shiftId);
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw);
        return new UUID(bb.getLong(), bb.getLong());
    }

    /** MONTHLY_CLOSE 失敗イベント（payload.operation 指定）。 */
    protected long failedEvents(Long org, String operation) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM shift_budget_failed_events WHERE organization_id = ? "
                + "AND event_type = 'MONTHLY_CLOSE' AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.operation')) = ?",
                Long.class, org, operation);
        return n == null ? 0 : n;
    }

    protected long failedEventsAny(Long org) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM shift_budget_failed_events WHERE organization_id = ?",
                Long.class, org);
        return n == null ? 0 : n;
    }

    protected static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    protected static YearMonth ym(String key) {
        return YearMonth.parse(key);
    }
}
