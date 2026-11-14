package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.budget.BudgetCategoryType;
import com.mannschaft.app.budget.entity.BudgetCategoryEntity;
import com.mannschaft.app.budget.entity.BudgetFiscalYearEntity;
import com.mannschaft.app.budget.repository.BudgetCategoryRepository;
import com.mannschaft.app.budget.repository.BudgetFiscalYearRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261021-0718 AC10: 実フィルタで未認証の公開取消を拒否し、非空の予算記録を保つ。
 *
 * <p>金型はScheduleKeepUnauthenticatedContractIT。B0の実SecurityFilterChainを通し、認証情報を
 * リクエストへ与えない。SecurityConfigはCSRF無効・deny-by-default・未認証entry point=401であり、
 * 有効なPUBLISHED→ADJUSTING遷移がCSRFや不在IDではなく認証境界で拒否されることを検査する。
 * 外側Transactionalで包まず、fixtureをcommitし、遅れて副作用が発生しないことも観測する。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("0718 未認証の公開取消は401でシフト・予算・監査を変更しない（実filters/DB）")
class ShiftBudgetUnauthenticatedTransitionIT extends AbstractMySqlIntegrationTest {
    private static final long GENERATION = 1L;
    private static final BigDecimal RATE = new BigDecimal("1250.00");
    private static final BigDecimal HOURS = new BigDecimal("8.00");
    private static final BigDecimal AMOUNT = new BigDecimal("10000.00");

    @Autowired private MockMvc mockMvc;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private BudgetFiscalYearRepository fiscalYears;
    @Autowired private BudgetCategoryRepository categories;
    @Autowired private ShiftScheduleRepository schedules;
    @Autowired private ShiftSlotRepository slots;
    @Autowired private ShiftBudgetAllocationRepository allocations;
    @Autowired private ShiftBudgetConsumptionRepository consumptions;
    @Autowired private FeatureFlagRepository featureFlags;
    @Autowired private CacheManager cacheManager;
    @Autowired @Qualifier("event-pool") private ThreadPoolTaskExecutor eventExecutor;
    @PersistenceContext private EntityManager em;

    private Long organizationId;
    private Long teamId;
    private Long actorId;
    private Long fiscalYearId;
    private Long categoryId;
    private Long scheduleId;
    private Long allocationId;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        FeatureFlagTestSupport.enable(featureFlags, cacheManager, "FEATURE_SHIFT_ENABLED");
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 28);
        transactionTemplate.executeWithoutResult(tx -> {
            organizationId = TeamOrgFixtureHelper.insertOrganization(em, "0718未認証組織", "o-" + nonce);
            teamId = TeamOrgFixtureHelper.insertTeam(em, "0718未認証チーム", "t-" + nonce);
            actorId = users.save(ShiftBudgetPublicationGenerationTestFixture.user(nonce, "401")).getId();
            TeamOrgFixtureHelper.insertTeamOrgMembership(em, teamId, organizationId, "ACTIVE", LocalDateTime.now());
            MembershipTestHelper.insertMembership(em, actorId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, actorId, "ADMIN", teamId, null);
            var date = ShiftBudgetPublicationGenerationTestFixture.SLOT_DATE;
            fiscalYearId = fiscalYears.save(BudgetFiscalYearEntity.builder()
                    .scopeType("ORGANIZATION").scopeId(organizationId).name("0718未認証年度")
                    .startDate(date.withDayOfYear(1)).endDate(date.withMonth(12).withDayOfMonth(31))
                    .createdBy(actorId).version(0L).build()).getId();
            categoryId = categories.save(BudgetCategoryEntity.builder().fiscalYearId(fiscalYearId)
                    .name("0718未認証人件費").categoryType(BudgetCategoryType.EXPENSE).version(0L).build()).getId();
            var schedule = ShiftBudgetPublicationGenerationTestFixture.schedule(teamId, actorId);
            schedule.publish(actorId);
            schedule.setPublicationGeneration(GENERATION);
            scheduleId = schedules.save(schedule).getId();
            Long slotId = slots.save(ShiftBudgetPublicationGenerationTestFixture.slot(scheduleId, actorId)).getId();
            allocationId = allocations.save(ShiftBudgetPublicationGenerationTestFixture
                    .allocation(organizationId, teamId, actorId).toBuilder().fiscalYearId(fiscalYearId)
                    .budgetCategoryId(categoryId).consumedAmount(AMOUNT).build()).getId();
            consumptions.save(ShiftBudgetConsumptionCancelRaceTestFixture.consumption(
                    allocationId, scheduleId, slotId, AMOUNT, ShiftBudgetConsumptionStatus.PLANNED).toBuilder()
                    .userId(actorId).publicationGeneration(GENERATION).hourlyRateSnapshot(RATE).hours(HOURS).build());
        });
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        // 認証境界が壊れていた失敗時にも、発行済みの実hookが終わってから自用fixtureを片付ける。
        await().atMost(Duration.ofSeconds(30)).until(() -> eventExecutor.getActiveCount() == 0
                && eventExecutor.getThreadPoolExecutor().getQueue().isEmpty());
        if (scheduleId != null) {
            jdbc.update("DELETE FROM shift_budget_consumptions WHERE shift_id = ?", scheduleId);
            jdbc.update("DELETE FROM shift_slots WHERE schedule_id = ?", scheduleId);
            jdbc.update("DELETE FROM shift_schedules WHERE id = ?", scheduleId);
        }
        if (allocationId != null) jdbc.update("DELETE FROM shift_budget_allocations WHERE id = ?", allocationId);
        if (categoryId != null) jdbc.update("DELETE FROM budget_categories WHERE id = ?", categoryId);
        if (fiscalYearId != null) jdbc.update("DELETE FROM budget_fiscal_years WHERE id = ?", fiscalYearId);
        if (teamId != null) {
            jdbc.update("DELETE FROM audit_logs WHERE team_id = ? OR organization_id = ?", teamId, organizationId);
            jdbc.update("DELETE FROM shift_budget_failed_events WHERE organization_id = ?", organizationId);
            jdbc.update("DELETE FROM user_roles WHERE team_id = ?", teamId);
            jdbc.update("DELETE FROM memberships WHERE scope_type = 'TEAM' AND scope_id = ?", teamId);
            jdbc.update("DELETE FROM team_org_memberships WHERE team_id = ?", teamId);
            jdbc.update("DELETE FROM teams WHERE id = ?", teamId);
        }
        if (organizationId != null) jdbc.update("DELETE FROM organizations WHERE id = ?", organizationId);
        if (actorId != null) jdbc.update("DELETE FROM users WHERE id = ?", actorId);
    }

    @Test
    @DisplayName("AC10 未認証のPUBLISHED→ADJUSTINGは401で、世代1・PLANNED・10000円・監査が不変")
    void 未認証の公開取消は401で永続化状態を変えない() throws Exception {
        Map<String, Object> parentBefore = parentState();
        List<Map<String, Object>> consumptionsBefore = consumptionState();
        BigDecimal amountBefore = consumedAmount();
        int auditsBefore = ownAuditCount();
        assertThat(parentBefore.get("status")).isEqualTo("PUBLISHED");
        assertThat(((Number) parentBefore.get("publication_generation")).longValue()).isEqualTo(GENERATION);
        assertThat(consumptionsBefore).hasSize(1);
        assertThat(consumptionsBefore.getFirst().get("status")).isEqualTo("PLANNED");
        assertThat(amountBefore).isEqualByComparingTo(AMOUNT);
        assertThat(auditsBefore).isZero();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        // Authorization header・cookie・認証用request postprocessor・CSRF tokenを一切与えない。
        mockMvc.perform(post("/api/v1/shifts/schedules/{id}/transition", scheduleId)
                        .param("status", "ADJUSTING"))
                .andExpect(status().isUnauthorized());

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(parentState()).as("親の状態・世代・公開情報").isEqualTo(parentBefore);
            assertThat(consumptionState()).as("消化の件数・状態・世代・金額・取消情報").isEqualTo(consumptionsBefore);
            assertThat(consumedAmount()).isEqualByComparingTo(amountBefore);
            assertThat(ownAuditCount()).as("自用scopeの成功監査も新規作成されない").isEqualTo(auditsBefore);
        });
    }

    private Map<String, Object> parentState() {
        return jdbc.queryForMap("SELECT status, publication_generation, published_at, published_by "
                + "FROM shift_schedules WHERE id = ?", scheduleId);
    }

    private List<Map<String, Object>> consumptionState() {
        return jdbc.queryForList("SELECT id, status, publication_generation, amount, cancelled_at, cancel_reason "
                + "FROM shift_budget_consumptions WHERE shift_id = ? ORDER BY id", scheduleId);
    }

    private BigDecimal consumedAmount() {
        return jdbc.queryForObject("SELECT consumed_amount FROM shift_budget_allocations WHERE id = ?",
                BigDecimal.class, allocationId);
    }

    private int ownAuditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE team_id = ? OR organization_id = ?",
                Integer.class, teamId, organizationId);
    }
}
