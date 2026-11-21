package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.shift.ShiftPeriodType;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetConsumptionService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * CMP-261115-1821 G08: 無効時給を実REQUIRES_NEWの副作用前に拒否する初期RED。
 *
 * <p>fixtureは明示commitし、実Beanで正数のPLANNEDを記録してから不正な時給を渡す。
 * 例外と、独立接続から再読した元行・消費額の不変を検査する。DB制約例外を引数拒否の合格にしない。
 * 外側をrollbackするテストTXで包まないので、是正前にcommitされた0円置換も検出できる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261115-1821 計上時給の正数契約（初期RED）")
class ShiftBudgetPositiveRateContractIT extends AbstractMySqlIntegrationTest {

    private static final LocalDate WORK_DATE = LocalDate.of(2026, 7, 15);
    private static final BigDecimal HOURS = new BigDecimal("8.00");
    private static final BigDecimal ORIGINAL_AMOUNT = new BigDecimal("9600.00");

    @Autowired private ShiftBudgetConsumptionService service;
    @Autowired private UserRepository users;
    @Autowired private ShiftBudgetAllocationRepository allocations;
    @Autowired private ShiftScheduleRepository schedules;
    @Autowired private ShiftSlotRepository slots;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager em;

    private Long orgId;
    private Long teamId;
    private Long userId;
    private Long scheduleId;
    private Long slotId;
    private Long allocationId;

    @BeforeEach
    void 正数で記録済みの実消化をcommitする() {
        new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            orgId = TeamOrgFixtureHelper.insertOrganization(em, "1821計上組織", "o-" + nonce);
            teamId = TeamOrgFixtureHelper.insertTeam(em, "1821計上チーム", "t-" + nonce);
            userId = users.save(UserEntity.builder().email("positive-rate-" + nonce + "@example.com")
                    .lastName("試練").firstName("計上対象").displayName("計上対象")
                    .status(UserEntity.UserStatus.ACTIVE).isSearchable(false)
                    .locale("ja").timezone("Asia/Tokyo").build()).getId();
            scheduleId = schedules.save(ShiftScheduleEntity.builder().teamId(teamId)
                    .title("正数時給の計上ガード").periodType(ShiftPeriodType.WEEKLY)
                    .startDate(WORK_DATE).endDate(WORK_DATE.plusDays(6))
                    .status(ShiftScheduleStatus.PUBLISHED).build()).getId();
            slotId = slots.save(ShiftSlotEntity.builder().scheduleId(scheduleId).slotDate(WORK_DATE)
                    .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0)).requiredCount(1).build()).getId();
            allocationId = allocations.save(ShiftBudgetAllocationEntity.builder().organizationId(orgId).teamId(teamId)
                    .fiscalYearId(1821L).budgetCategoryId(1821L).periodStart(WORK_DATE.withDayOfMonth(1))
                    .periodEnd(WORK_DATE.withDayOfMonth(WORK_DATE.lengthOfMonth()))
                    .allocatedAmount(new BigDecimal("500000")).consumedAmount(BigDecimal.ZERO)
                    .confirmedAmount(BigDecimal.ZERO).currency("JPY").createdBy(userId).version(0L).build()).getId();
        });
        service.recordSingleConsumption(allocationId, scheduleId, slotId, userId,
                new BigDecimal("1200.00"), HOURS, WORK_DATE);
        assertThat(amount()).isEqualByComparingTo(ORIGINAL_AMOUNT);
    }

    @AfterEach
    void commit済みの自作fixtureだけを撤去する() {
        if (allocationId != null) {
            jdbc.update("DELETE FROM shift_budget_consumptions WHERE allocation_id = ?", allocationId);
            jdbc.update("DELETE FROM shift_budget_allocations WHERE id = ?", allocationId);
        }
        if (scheduleId != null) {
            jdbc.update("DELETE FROM shift_slots WHERE schedule_id = ?", scheduleId);
            jdbc.update("DELETE FROM shift_schedules WHERE id = ?", scheduleId);
        }
        if (teamId != null) {
            jdbc.update("DELETE FROM teams WHERE id = ?", teamId);
        }
        if (orgId != null) {
            jdbc.update("DELETE FROM organizations WHERE id = ?", orgId);
        }
        if (userId != null) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @ParameterizedTest(name = "G08 hourlyRate={0}: 元PLANNEDと消費額を変更せず拒否")
    @NullSource
    @ValueSource(strings = {"0.00", "-1.00"})
    void G08_nullゼロ負数は既存消化を取り消す前に拒否する(String rateText) {
        BigDecimal invalidRate = rateText == null ? null : new BigDecimal(rateText);
        var before = jdbc.queryForList("SELECT * FROM shift_budget_consumptions WHERE allocation_id = ? ORDER BY id",
                allocationId);
        assertThat(before).hasSize(1);
        assertThat(before.getFirst().get("status")).isEqualTo("PLANNED");

        Throwable failure = catchThrowable(() -> service.recordSingleConsumption(
                allocationId, scheduleId, slotId, userId, invalidRate, HOURS, WORK_DATE));

        var after = jdbc.queryForList("SELECT * FROM shift_budget_consumptions WHERE allocation_id = ? ORDER BY id",
                allocationId);
        assertSoftly(softly -> {
            softly.assertThat(failure).as("DB制約ではなく、副作用前の無効引数拒否")
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(after).as("元PLANNEDの状態・金額・時給と行数を維持し、0円行を作らない")
                    .isEqualTo(before);
            softly.assertThat(amount()).as("元の予算消費額を維持")
                    .isEqualByComparingTo(ORIGINAL_AMOUNT);
        });
    }

    private BigDecimal amount() {
        return jdbc.queryForObject("SELECT consumed_amount FROM shift_budget_allocations WHERE id = ?",
                BigDecimal.class, allocationId);
    }
}
