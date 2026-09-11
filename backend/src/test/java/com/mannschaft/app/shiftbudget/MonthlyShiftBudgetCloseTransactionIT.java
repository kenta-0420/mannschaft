package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.budget.entity.BudgetTransactionEntity;
import com.mannschaft.app.budget.repository.BudgetTransactionRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.shiftbudget.service.MonthlyShiftBudgetCloseService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * CMP-260910-1556 — シフト予算 月次締めのトランザクション境界を実 DB で検証する。
 *
 * <h2>何を実証するテストか</h2>
 * <p>是正前、{@code MonthlyShiftBudgetCloseService#doCloseForOrg} は同一 Bean 内の
 * {@code closeOneAllocation}（{@code @Transactional(propagation = REQUIRES_NEW)}）を
 * <b>自己呼び出し</b>していた。Spring の AOP プロキシを経由しないためアノテーションは効かず、
 * 呼び出し元が {@code Propagation.NEVER}（トランザクション不在）であることと相まって、
 * {@code closeOneAllocation} は<b>トランザクションなし</b>で実行されていた。その結果:</p>
 * <ul>
 *   <li>{@code @Modifying} クエリ {@code incrementConfirmedAmount} が
 *       {@code InvalidDataAccessApiUsageException} を投げ、月次締めは<b>初回必ず 500</b></li>
 *   <li>直前の {@code consumptionRepository.save} は Spring Data 既定の
 *       {@code @Transactional} で単独コミット済みのため、消化だけ CONFIRMED になり
 *       {@code allocation.confirmed_amount} は 0 のまま残る<b>部分適用</b>が巻き戻らない</li>
 * </ul>
 *
 * <h2>なぜ統合テストなのか</h2>
 * <p>自己呼び出しは「プロキシを経由したか」という実行時の性質であり、
 * サービスを {@code new} して mock を挿すユニットテストでは<b>構造的に検出できない</b>
 * （mock は素通しで成功してしまう）。プロキシが実在する Spring コンテキストと、
 * {@code @Modifying} を本当に拒否する実 DB の両方が要る。</p>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>{@code close} / {@code closeFromBatch} は {@code Propagation.NEVER} を宣言しており、
 * テストが外側トランザクションを張ると {@code IllegalTransactionStateException} で
 * 本題に到達する前に落ちる。フィクスチャ投入と検証読み取りは
 * {@link TransactionTemplate} / {@link JdbcTemplate} で明示的にコミットする
 * （金型: {@code ShiftManualArchiveConsumptionCancelIT}）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@TestPropertySource(properties = "feature.shift-budget.enabled=true")
@DisplayName("CMP-260910-1556 月次締めのトランザクション境界（実DB）")
class MonthlyShiftBudgetCloseTransactionIT extends AbstractMySqlIntegrationTest {

    private static final YearMonth TARGET_MONTH = YearMonth.of(2026, 6);
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 6, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 6, 30);
    private static final BigDecimal AMOUNT_A = new BigDecimal("4800");
    private static final BigDecimal AMOUNT_B = new BigDecimal("5200");
    private static final BigDecimal TOTAL = new BigDecimal("10000");

    /**
     * 消化レコードの slot_id 採番器。
     *
     * <p>{@code shift_budget_consumptions} には
     * {@code uq_sbc_slot_user_status (slot_id, user_id, status, deleted_at)} の UNIQUE 制約があり、
     * 同一スロット・同一ユーザーで PLANNED を 2 件作れない。検体は「複数の消化が 1 トランザクションで
     * まとめて CONFIRMED 化されること」を見たいので、スロットを別々に採番する。</p>
     */
    private static final AtomicLong SLOT_SEQ = new AtomicLong(1);

    /** 失敗注入時に投げる例外のメッセージ。 */
    private static final String INJECTED = "CMP-260910-1556: 仕訳 INSERT 失敗を模す";

    @Autowired
    private MonthlyShiftBudgetCloseService closeService;

    /**
     * 失敗注入用の spy。
     *
     * <p>DB 制約（FK / CHECK）で落とす手も検討したが、統合テストのスキーマは
     * {@code spring.flyway.enabled=false} + {@code ddl-auto: create} により
     * <b>Hibernate がエンティティから生成</b>している。Flyway の DDL にある
     * {@code fk_bt_fiscal_year} や {@code chk_sba_consumed} はテスト DB に存在せず、
     * 不正な値を入れても例外にならない（実際に 2 度、無言で素通りする赤を踏んだ）。
     * よって「allocation 単位の後段処理が失敗する」状況は spy で作る。</p>
     */
    @MockitoSpyBean
    private BudgetTransactionRepository budgetTransactionRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ShiftBudgetAllocationRepository allocationRepository;

    @Autowired
    private ShiftBudgetConsumptionRepository consumptionRepository;

    @PersistenceContext
    private EntityManager em;

    private Long orgId;
    private Long teamId;
    private Long actorId;
    private Long fiscalYearId;
    private Long categoryId;

    @BeforeEach
    void setUp() {
        String nonce = nonce();
        transactionTemplate.executeWithoutResult(tx -> {
            orgId = insertOrganization("CMP1556 組織 " + nonce);
            teamId = insertTeam("CMP1556 チーム " + nonce);
            insertTeamOrgMembership(teamId, orgId);

            actorId = insertUser("cmp1556-admin-" + nonce + "@example.com");
            MembershipTestHelper.insertMembership(em, actorId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            // BUDGET_ADMIN 権限の解決を SYSTEM_ADMIN で短絡させる（本題は権限ではなく tx 境界）
            MembershipTestHelper.insertUserRole(em, actorId, "SYSTEM_ADMIN", null, null);

            fiscalYearId = insertFiscalYear("CMP1556 年度 " + nonce);
            categoryId = insertCategory(fiscalYearId, "CMP1556 費目 " + nonce);
            em.flush();
        });
        setAuth(actorId);
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-1: 初回から成功する（REQUIRES_NEW がプロキシ経由で実際に効く）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-1 月次締めは初回の呼び出しで成功し、confirmed_amount が加算される")
    void 月次締めは初回から成功する() {
        Long allocationId = seedAllocationWithPlannedConsumptions();

        MonthlyShiftBudgetCloseService.CloseResult result =
                closeService.close(orgId, TARGET_MONTH);

        assertThat(result.closedAllocations())
                .as("自己呼び出しのままだと incrementConfirmedAmount が例外になり 1 件も締まらない")
                .isEqualTo(1);
        assertThat(result.closedConsumptions()).isEqualTo(2);

        assertThat(confirmedAmount(allocationId))
                .as("確定額が 0 のままなら REQUIRES_NEW がプロキシを経由していない")
                .isEqualByComparingTo(TOTAL);
        assertThat(consumptionStatuses(allocationId))
                .containsExactlyInAnyOrder("CONFIRMED", "CONFIRMED");
        assertThat(summaryTransactionCount(allocationId))
                .as("月次集計仕訳が 1 件だけ作られること")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("AC-1 2 回目の呼び出しは ALREADY_CLOSED として skip し、確定額を二重加算しない")
    void 二回目は冪等にskipされる() {
        Long allocationId = seedAllocationWithPlannedConsumptions();

        closeService.close(orgId, TARGET_MONTH);
        MonthlyShiftBudgetCloseService.CloseResult second =
                closeService.close(orgId, TARGET_MONTH);

        assertThat(second.closedAllocations()).isZero();
        assertThat(second.alreadyClosedAllocations()).isEqualTo(1);
        assertThat(confirmedAmount(allocationId)).isEqualByComparingTo(TOTAL);
        assertThat(summaryTransactionCount(allocationId)).isEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-2: 途中で落ちても部分適用が残らない（原子性）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-2 仕訳 INSERT が失敗したら消化の CONFIRMED 化も確定額の加算も巻き戻る")
    void 失敗時に部分適用が残らない() {
        // 消化の CONFIRMED 化 → confirmed_amount 加算 → 仕訳 INSERT の順で進んだ末に
        // 最後の一手で落とす。是正前はここまでに書いたものが個別コミット済みで残った。
        Long allocationId = seedAllocationWithPlannedConsumptions();
        failOnSummaryTransactionInsert();

        assertThatThrownBy(() -> closeService.close(orgId, TARGET_MONTH))
                .as("仕訳が書けない以上、締めは失敗として伝播しなければならない")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(INJECTED);

        assertThat(consumptionStatuses(allocationId))
                .as("消化だけ CONFIRMED で残ると、確定額 0 円のまま永久に締め直せなくなる（本欠陥の実害）")
                .containsExactlyInAnyOrder("PLANNED", "PLANNED");
        assertThat(confirmedAmount(allocationId)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summaryTransactionCount(allocationId)).isZero();
    }

    @Test
    @DisplayName("AC-2 巻き戻った allocation は、原因を取り除けばそのまま締め直せる")
    void 巻き戻った割当は再実行で締められる() {
        Long allocationId = seedAllocationWithPlannedConsumptions();
        failOnSummaryTransactionInsert();

        assertThatThrownBy(() -> closeService.close(orgId, TARGET_MONTH))
                .isInstanceOf(RuntimeException.class);

        // 原因（仕訳 INSERT の失敗）を取り除く。
        // doCallRealMethod は Spring Data のインターフェースプロキシに対しては使えない
        // （"Cannot call abstract real method" になる）。spy を reset すると
        // 既定の「実体へ委譲する」振る舞いに戻る。
        reset(budgetTransactionRepository);

        setAuth(actorId);
        assertThatCode(() -> closeService.close(orgId, TARGET_MONTH))
                .as("部分適用が残っていれば PLANNED が 0 件になり確定額が 0 円のまま固着する")
                .doesNotThrowAnyException();
        assertThat(confirmedAmount(allocationId)).isEqualByComparingTo(TOTAL);
    }

    // ═══════════════════════════════════════════════════════════════
    // ヘルパー
    // ═══════════════════════════════════════════════════════════════

    /** 月次集計仕訳の INSERT だけを失敗させる（消化の更新は既に済んでいる段階で落とす）。 */
    private void failOnSummaryTransactionInsert() {
        doThrow(new IllegalStateException(INJECTED))
                .when(budgetTransactionRepository).save(any(BudgetTransactionEntity.class));
    }

    private Long seedAllocationWithPlannedConsumptions() {
        return seedAllocationWithPlannedConsumptions(TOTAL);
    }

    private Long seedAllocationWithPlannedConsumptions(BigDecimal consumedAmount) {
        return transactionTemplate.execute(tx -> {
            ShiftBudgetAllocationEntity allocation = allocationRepository.save(
                    ShiftBudgetAllocationEntity.builder()
                            .organizationId(orgId)
                            .teamId(teamId)
                            .fiscalYearId(fiscalYearId)
                            .budgetCategoryId(categoryId)
                            .periodStart(PERIOD_START)
                            .periodEnd(PERIOD_END)
                            .allocatedAmount(new BigDecimal("500000"))
                            .consumedAmount(consumedAmount)
                            .confirmedAmount(BigDecimal.ZERO)
                            .currency("JPY")
                            .createdBy(actorId)
                            .version(0L)
                            .build());
            savePlanned(allocation.getId(), AMOUNT_A);
            savePlanned(allocation.getId(), AMOUNT_B);
            return allocation.getId();
        });
    }

    private void savePlanned(Long allocationId, BigDecimal amount) {
        long slotId = SLOT_SEQ.getAndIncrement();
        consumptionRepository.save(ShiftBudgetConsumptionEntity.builder()
                .allocationId(allocationId)
                .shiftId(1L)
                .slotId(slotId)
                .userId(actorId)
                .hourlyRateSnapshot(new BigDecimal("1200.00"))
                .hours(new BigDecimal("4.00"))
                .amount(amount)
                .currency("JPY")
                .status(ShiftBudgetConsumptionStatus.PLANNED)
                .recordedAt(LocalDateTime.of(2026, 6, 1, 9, 0))
                .build());
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private BigDecimal confirmedAmount(Long allocationId) {
        return jdbcTemplate.queryForObject(
                "SELECT confirmed_amount FROM shift_budget_allocations WHERE id = ?",
                BigDecimal.class, allocationId);
    }

    private List<String> consumptionStatuses(Long allocationId) {
        return jdbcTemplate.queryForList(
                "SELECT status FROM shift_budget_consumptions WHERE allocation_id = ? AND deleted_at IS NULL",
                String.class, allocationId);
    }

    private int summaryTransactionCount(Long allocationId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM budget_transactions WHERE source_type = ? AND source_id = ?",
                Integer.class,
                MonthlyShiftBudgetCloseService.SOURCE_TYPE_SHIFT_BUDGET_MONTHLY, allocationId);
        return n == null ? 0 : n;
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
                                + "VALUES (:email, 'CMP1556', 'テスト', 'CMP1556 管理者', 'ACTIVE', "
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

    private Long insertFiscalYear(String name) {
        em.createNativeQuery(
                        "INSERT INTO budget_fiscal_years (scope_type, scope_id, name, start_date, end_date, "
                                + "status, created_by, version, created_at, updated_at) "
                                + "VALUES ('ORGANIZATION', :orgId, :name, '2026-04-01', '2027-03-31', "
                                + "'OPEN', :userId, 0, NOW(), NOW())")
                .setParameter("orgId", orgId)
                .setParameter("name", name)
                .setParameter("userId", actorId)
                .executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT id FROM budget_fiscal_years WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertCategory(Long fyId, String name) {
        em.createNativeQuery(
                        "INSERT INTO budget_categories (fiscal_year_id, name, category_type, sort_order, "
                                + "version, created_at, updated_at) "
                                + "VALUES (:fyId, :name, 'EXPENSE', 0, 0, NOW(), NOW())")
                .setParameter("fyId", fyId)
                .setParameter("name", name)
                .executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT id FROM budget_categories WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
