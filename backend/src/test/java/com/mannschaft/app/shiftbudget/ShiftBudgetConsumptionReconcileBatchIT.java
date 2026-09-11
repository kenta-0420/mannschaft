package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.shift.ShiftPeriodType;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shiftbudget.batch.ShiftBudgetConsumptionReconcileBatchService;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260909-1445 AC-09 / AC-10 — 孤児 PLANNED 消化の整合バッチの実 DB 検証。
 *
 * <p>マスターの御裁可により、既に取り残されてしまった消化データは
 * 「原因系に依らず状態だけを見て収束させる」整合バッチで救済する。本 IT はその 2 面を固定する。</p>
 * <ul>
 *   <li><b>AC-09</b>: ARCHIVED / 論理削除済みシフトに残った PLANNED 消化を検出して取り消し、
 *       {@code consumed_amount} を減算する。再実行しても 0 件（冪等）。</li>
 *   <li><b>AC-10</b>: シフトが PUBLISHED のままの<b>健全な</b> PLANNED 消化には一切触れない。
 *       ここが守られないと「予算を積んだ端から整合バッチが消していく」最悪の回帰になる。</li>
 * </ul>
 *
 * <p>クラスに {@code @Transactional} を付けないのは、バッチが {@code cancelAllForShift}
 * （{@code REQUIRES_NEW}）で 1 シフトずつ独立にコミットするためである。テストを外側の
 * トランザクションで包むと、内側の新規トランザクションからは未コミットのフィクスチャが
 * 見えず、検出 0 件で偽の緑になる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260909-1445 孤児消化の整合バッチ（実DB）")
class ShiftBudgetConsumptionReconcileBatchIT extends AbstractMySqlIntegrationTest {

    private static final BigDecimal AMOUNT = new BigDecimal("8000.00");

    @Autowired
    private ShiftBudgetConsumptionReconcileBatchService reconcileBatchService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ShiftScheduleRepository scheduleRepository;

    @Autowired
    private ShiftSlotRepository slotRepository;

    @Autowired
    private ShiftBudgetAllocationRepository allocationRepository;

    @Autowired
    private ShiftBudgetConsumptionRepository consumptionRepository;

    @PersistenceContext
    private EntityManager em;

    private Long orgId;
    private Long teamId;

    @BeforeEach
    void setUp() {
        String nonce = nonce();
        transactionTemplate.executeWithoutResult(tx -> {
            orgId = insertOrganization("CMP1445R 組織 " + nonce);
            teamId = insertTeam("CMP1445R チーム " + nonce);
            insertTeamOrgMembership(teamId, orgId);
        });
    }

    @Test
    @DisplayName("AC-09 ARCHIVED シフトに残った孤児 PLANNED 消化を取り消し、再実行では 0 件（冪等）")
    void 孤児消化を取り消し再実行は0件() {
        Fixture orphan = seedConsumption(ShiftScheduleStatus.ARCHIVED, false);

        int cancelled = reconcileBatchService.reconcileOrphanConsumptions();

        assertThat(cancelled)
                .as("孤児が 1 件も検出されないなら、検出クエリが状態のズレを見ていない")
                .isGreaterThanOrEqualTo(1);
        assertThat(consumptionStatus(orphan.consumptionId)).isEqualTo("CANCELLED");
        assertThat(consumedAmount(orphan.allocationId))
                .as("取消と対で consumed_amount が減算されること")
                .isEqualByComparingTo(BigDecimal.ZERO);

        int second = reconcileBatchService.reconcileOrphanConsumptions();

        assertThat(second)
                .as("再実行で再び取り消すなら冪等でない（二重減算で残高がマイナスに振れる）")
                .isZero();
        assertThat(consumedAmount(orphan.allocationId))
                .as("再実行後も残高が動かないこと")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("AC-09 論理削除済みシフトに残った孤児 PLANNED 消化も取り消される")
    void 論理削除シフトの孤児消化も取り消される() {
        Fixture orphan = seedConsumption(ShiftScheduleStatus.PUBLISHED, true);

        reconcileBatchService.reconcileOrphanConsumptions();

        assertThat(consumptionStatus(orphan.consumptionId)).isEqualTo("CANCELLED");
        assertThat(consumedAmount(orphan.allocationId)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("AC-10 シフトが PUBLISHED の健全な PLANNED 消化は 1 件も取り消さない")
    void 健全な消化には触れない() {
        Fixture healthy = seedConsumption(ShiftScheduleStatus.PUBLISHED, false);

        reconcileBatchService.reconcileOrphanConsumptions();

        assertThat(consumptionStatus(healthy.consumptionId))
                .as("公開中シフトの消化を取り消したら、予算を積んだ端からバッチが消していくことになる")
                .isEqualTo("PLANNED");
        assertThat(consumedAmount(healthy.allocationId))
                .as("健全な消化の残高を減算してはならない")
                .isEqualByComparingTo(AMOUNT);
    }

    // ═══════════════════════════════════════════════════════════════
    // フィクスチャ / ヘルパー
    // ═══════════════════════════════════════════════════════════════

    private record Fixture(Long scheduleId, Long allocationId, Long consumptionId) {
    }

    private Fixture seedConsumption(ShiftScheduleStatus status, boolean softDeleted) {
        return transactionTemplate.execute(tx -> {
            ShiftScheduleEntity schedule = scheduleRepository.save(ShiftScheduleEntity.builder()
                    .teamId(teamId)
                    .title("CMP1445R シフト " + nonce())
                    .periodType(ShiftPeriodType.WEEKLY)
                    .startDate(LocalDate.of(2026, 7, 1))
                    .endDate(LocalDate.of(2026, 7, 7))
                    .status(status)
                    .publishedAt(LocalDateTime.of(2026, 6, 20, 10, 0))
                    .build());
            Long scheduleId = schedule.getId();
            if (softDeleted) {
                // softDelete() 相当を SQL で直接立てる（エンティティ経由だと deletedAt が
                // 実行時刻になるだけで意味は同じだが、フィクスチャの意図を SQL に明示する）
                em.createNativeQuery("UPDATE shift_schedules SET deleted_at = NOW() WHERE id = :id")
                        .setParameter("id", scheduleId)
                        .executeUpdate();
            }

            ShiftSlotEntity slot = slotRepository.save(ShiftSlotEntity.builder()
                    .scheduleId(scheduleId)
                    .slotDate(LocalDate.of(2026, 7, 1))
                    .startTime(LocalTime.of(9, 0))
                    .endTime(LocalTime.of(17, 0))
                    .requiredCount(1)
                    .build());

            ShiftBudgetAllocationEntity allocation = allocationRepository.save(
                    ShiftBudgetAllocationEntity.builder()
                            .organizationId(orgId)
                            .teamId(teamId)
                            .fiscalYearId(9101L)
                            .budgetCategoryId(9102L)
                            .periodStart(LocalDate.of(2026, 7, 1))
                            .periodEnd(LocalDate.of(2026, 7, 31))
                            .allocatedAmount(new BigDecimal("300000.00"))
                            .consumedAmount(AMOUNT)
                            .confirmedAmount(BigDecimal.ZERO)
                            .currency("JPY")
                            // created_by は NOT NULL。省略すると INSERT が
                            // 「Column 'created_by' cannot be null」で落ちる
                            .createdBy(9999L)
                            .version(0L)
                            .build());

            ShiftBudgetConsumptionEntity consumption = consumptionRepository.save(
                    ShiftBudgetConsumptionEntity.builder()
                            .allocationId(allocation.getId())
                            .shiftId(scheduleId)
                            .slotId(slot.getId())
                            .userId(9999L)
                            .hourlyRateSnapshot(new BigDecimal("1000.00"))
                            .hours(new BigDecimal("8.00"))
                            .amount(AMOUNT)
                            .currency("JPY")
                            .status(ShiftBudgetConsumptionStatus.PLANNED)
                            .recordedAt(LocalDateTime.now())
                            .build());

            return new Fixture(scheduleId, allocation.getId(), consumption.getId());
        });
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
}
