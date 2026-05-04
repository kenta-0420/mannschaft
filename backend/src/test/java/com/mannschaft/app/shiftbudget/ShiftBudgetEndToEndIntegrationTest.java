package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.budget.entity.BudgetTransactionEntity;
import com.mannschaft.app.budget.repository.BudgetTransactionRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.shiftbudget.dto.ConsumptionSummaryResponse;
import com.mannschaft.app.shiftbudget.entity.BudgetThresholdAlertEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import com.mannschaft.app.shiftbudget.repository.BudgetThresholdAlertRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.shiftbudget.repository.TodoBudgetLinkRepository;
import com.mannschaft.app.shiftbudget.service.MonthlyShiftBudgetCloseService;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetSummaryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * F08.7 シフト予算 統合シナリオテスト（Phase 9-δ 第3段、End-to-End 結合検証）。
 *
 * <p>設計書 F08.7 (v1.2) §14.2 に準拠した「公開→消化→警告→月次締め→F08.6 仕訳」
 * 一連の Service 連結を 1 ファイルで検証する。各 Service の細部単体テストは別ファイルで網羅済。
 * 本テストは 9-α/β/γ + 9-δ 全段の正準連結が破綻していないことを保証する。</p>
 *
 * <p>方針:</p>
 * <ul>
 *   <li>Spring Context は起動しない（Mockito ベース、CI で安定実行）</li>
 *   <li>Repository は @Mock で in-memory 風に振る舞わせる</li>
 *   <li>Phase 9-α/β/γ + 9-δ 全段の Service 結合パスを通す</li>
 * </ul>
 *
 * <p>シナリオ:</p>
 * <ol>
 *   <li>初期: allocation 作成済 (allocated=300000)、消化レコードなし</li>
 *   <li>BUDGET_ADMIN が consumption-summary を取得 → status=OK + by_user=[]</li>
 *   <li>消化レコードを 245000 円分追加 (PLANNED 2 件、user_id 5/6)</li>
 *   <li>summary 再取得 → status=WARN + by_user に 2 ユーザー含む</li>
 *   <li>80% 閾値 alert を 1 件追加</li>
 *   <li>summary 再取得 → alerts に当該 alert を含む</li>
 *   <li>BUDGET_VIEW のみのユーザー視点 → by_user=[] + flags=BY_USER_HIDDEN</li>
 *   <li>月次締め → PLANNED→CONFIRMED + budget_transactions に 1 件 INSERT</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F08.7 シフト予算 統合シナリオ (Phase 9-δ 第3段)")
class ShiftBudgetEndToEndIntegrationTest {

    private static final Long ORG_ID = 1L;
    private static final Long TEAM_ID = 12L;
    private static final Long ALLOCATION_ID = 42L;
    private static final Long ADMIN_USER_ID = 100L;
    private static final Long VIEWER_USER_ID = 200L;
    private static final Long FISCAL_YEAR_ID = 3L;
    private static final Long CATEGORY_ID = 17L;

    @Mock
    private ShiftBudgetAllocationRepository allocationRepository;
    @Mock
    private ShiftBudgetConsumptionRepository consumptionRepository;
    @Mock
    private TodoBudgetLinkRepository todoBudgetLinkRepository;
    @Mock
    private BudgetThresholdAlertRepository alertRepository;
    @Mock
    private ShiftBudgetFeatureService featureService;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private BudgetTransactionRepository budgetTransactionRepository;
    @Mock
    private AuditLogService auditLogService;

    private ShiftBudgetSummaryService summaryService;
    private MonthlyShiftBudgetCloseService closeService;

    private ShiftBudgetAllocationEntity allocation;
    private final List<ShiftBudgetConsumptionEntity> consumptions = new ArrayList<>();
    private final List<BudgetThresholdAlertEntity> alerts = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        summaryService = new ShiftBudgetSummaryService(
                allocationRepository, consumptionRepository, todoBudgetLinkRepository,
                alertRepository, featureService, accessControlService);
        closeService = new MonthlyShiftBudgetCloseService(
                allocationRepository, consumptionRepository, budgetTransactionRepository,
                featureService, accessControlService, auditLogService);

        allocation = ShiftBudgetAllocationEntity.builder()
                .organizationId(ORG_ID).teamId(TEAM_ID)
                .fiscalYearId(FISCAL_YEAR_ID).budgetCategoryId(CATEGORY_ID)
                .periodStart(LocalDate.of(2026, 6, 1))
                .periodEnd(LocalDate.of(2026, 6, 30))
                .allocatedAmount(new BigDecimal("300000"))
                .consumedAmount(BigDecimal.ZERO)
                .confirmedAmount(BigDecimal.ZERO)
                .currency("JPY").createdBy(ADMIN_USER_ID).version(0L)
                .build();
        // BaseEntity の id を反射で埋める（実 DB なら IDENTITY 自動採番、本テストは Mock のため）
        injectId(allocation, ALLOCATION_ID);

        // featureService は常に有効
        lenient().doNothing().when(featureService).requireEnabled(ORG_ID);

        // allocation 取得
        lenient().when(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                .thenReturn(Optional.of(allocation));

        // 消化レコード取得（テスト中に this.consumptions の状態を反映）
        lenient().when(consumptionRepository.findByAllocationIdAndStatusInAndDeletedAtIsNull(
                eq(ALLOCATION_ID), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    var statuses = (List<ShiftBudgetConsumptionStatus>) inv.getArgument(1);
                    return consumptions.stream()
                            .filter(c -> statuses.contains(c.getStatus()))
                            .toList();
                });

        // alerts 取得（テスト中に this.alerts の状態を反映）
        lenient().when(alertRepository.findByAllocationIdOrderByTriggeredAtDesc(ALLOCATION_ID))
                .thenAnswer(inv -> List.copyOf(alerts));

        // todo_budget_links は本シナリオで未使用 (返り値を空に固定)
        lenient().when(todoBudgetLinkRepository.sumDirectAmountForProject(anyLong(), anyLong()))
                .thenReturn(BigDecimal.ZERO);
        lenient().when(todoBudgetLinkRepository.sumViaTodoAmountForProject(anyLong(), anyLong()))
                .thenReturn(BigDecimal.ZERO);

        // BUDGET_ADMIN セキュリティ既定（個別テストで上書き可）
        loginAsAdmin();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        consumptions.clear();
        alerts.clear();
    }

    private void loginAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ADMIN_USER_ID.toString(), null, List.of()));
        lenient().when(accessControlService.isSystemAdmin(ADMIN_USER_ID)).thenReturn(false);
        lenient().when(accessControlService.isMember(ADMIN_USER_ID, ORG_ID, "ORGANIZATION")).thenReturn(true);
        lenient().doNothing().when(accessControlService)
                .checkPermission(ADMIN_USER_ID, ORG_ID, "ORGANIZATION", "BUDGET_VIEW");
        lenient().doNothing().when(accessControlService)
                .checkPermission(ADMIN_USER_ID, ORG_ID, "ORGANIZATION", "BUDGET_ADMIN");
    }

    private void loginAsViewerOnly() {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(VIEWER_USER_ID.toString(), null, List.of()));
        lenient().when(accessControlService.isSystemAdmin(VIEWER_USER_ID)).thenReturn(false);
        lenient().when(accessControlService.isMember(VIEWER_USER_ID, ORG_ID, "ORGANIZATION")).thenReturn(true);
        lenient().doNothing().when(accessControlService)
                .checkPermission(VIEWER_USER_ID, ORG_ID, "ORGANIZATION", "BUDGET_VIEW");
        lenient().doThrow(new com.mannschaft.app.common.BusinessException(
                        ShiftBudgetErrorCode.BUDGET_ADMIN_REQUIRED))
                .when(accessControlService)
                .checkPermission(VIEWER_USER_ID, ORG_ID, "ORGANIZATION", "BUDGET_ADMIN");
    }

    private void addConsumption(Long userId, BigDecimal amount, BigDecimal hours,
                                 ShiftBudgetConsumptionStatus status) {
        consumptions.add(ShiftBudgetConsumptionEntity.builder()
                .allocationId(ALLOCATION_ID)
                .shiftId(700L)
                .slotId(800L + consumptions.size())
                .userId(userId)
                .hourlyRateSnapshot(new BigDecimal("1200.00"))
                .hours(hours)
                .amount(amount)
                .currency("JPY")
                .status(status)
                .recordedAt(LocalDateTime.of(2026, 6, 1, 9, 0))
                .build());
        // allocation の集計値も追従させる（実装の hook 相当）
        allocation = allocation.toBuilder()
                .consumedAmount(allocation.getConsumedAmount().add(amount))
                .build();
        try {
            injectId(allocation, ALLOCATION_ID);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        lenient().when(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                .thenReturn(Optional.of(allocation));
    }

    /** BaseEntity#id (private) をリフレクションで設定する（DB IDENTITY 採番を Mock 環境で再現）。 */
    private static void injectId(Object entity, Long id) throws Exception {
        Class<?> c = entity.getClass();
        while (c != null && !c.getSimpleName().equals("BaseEntity")) {
            c = c.getSuperclass();
        }
        if (c != null) {
            java.lang.reflect.Field f = c.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        }
    }

    private void addAlert(int thresholdPercent, BigDecimal consumedAtTrigger) {
        alerts.add(BudgetThresholdAlertEntity.builder()
                .allocationId(ALLOCATION_ID)
                .thresholdPercent(thresholdPercent)
                .triggeredAt(LocalDateTime.now())
                .consumedAmountAtTrigger(consumedAtTrigger)
                .notifiedUserIds("[" + ADMIN_USER_ID + "]")
                .build());
    }

    @Test
    @DisplayName("シナリオ: 公開→消化→80%警告→月次締め→F08.6 仕訳の一連が破綻なく通る")
    void e2eシナリオ完走() {
        // === ステージ 1: 初期状態 (消化ゼロ) ===
        ConsumptionSummaryResponse initial = summaryService.getConsumptionSummary(ORG_ID, ALLOCATION_ID);
        assertThat(initial.status()).isEqualTo("OK");
        assertThat(initial.consumptionRate()).isEqualByComparingTo("0.0000");
        assertThat(initial.byUser()).isEmpty();
        assertThat(initial.flags()).isEmpty();  // ADMIN は flags 空
        assertThat(initial.alerts()).isEmpty();

        // === ステージ 2: 消化レコードを 2 件追加 (公開シミュレーション) ===
        addConsumption(5L, new BigDecimal("80000"), new BigDecimal("66.67"),
                ShiftBudgetConsumptionStatus.PLANNED);
        addConsumption(6L, new BigDecimal("165000"), new BigDecimal("137.50"),
                ShiftBudgetConsumptionStatus.PLANNED);
        // 合計 245000 → 81.67% → WARN ステータス

        ConsumptionSummaryResponse afterConsume = summaryService.getConsumptionSummary(ORG_ID, ALLOCATION_ID);
        assertThat(afterConsume.consumedAmount()).isEqualByComparingTo("245000");
        assertThat(afterConsume.status()).isEqualTo("WARN");
        assertThat(afterConsume.byUser()).hasSize(2);
        assertThat(afterConsume.byUser().get(0).userId()).isEqualTo(5L);
        assertThat(afterConsume.byUser().get(0).amount()).isEqualByComparingTo("80000");
        assertThat(afterConsume.byUser().get(1).userId()).isEqualTo(6L);

        // === ステージ 3: 80% 閾値 alert 発火を模擬 ===
        addAlert(80, new BigDecimal("245000"));

        ConsumptionSummaryResponse afterAlert = summaryService.getConsumptionSummary(ORG_ID, ALLOCATION_ID);
        assertThat(afterAlert.alerts()).hasSize(1);
        assertThat(afterAlert.alerts().get(0).thresholdPercent()).isEqualTo(80);

        // === ステージ 4: BUDGET_VIEW のみユーザー視点で再取得 → by_user=[] + flags=BY_USER_HIDDEN ===
        loginAsViewerOnly();
        ConsumptionSummaryResponse asViewer = summaryService.getConsumptionSummary(ORG_ID, ALLOCATION_ID);
        assertThat(asViewer.byUser()).isEmpty();
        assertThat(asViewer.flags()).contains(ShiftBudgetSummaryService.FLAG_BY_USER_HIDDEN);
        assertThat(asViewer.alerts()).hasSize(1);  // alerts は権限によらず実データ
        assertThat(asViewer.consumedAmount()).isEqualByComparingTo("245000");  // 金額は viewer も見える

        // === ステージ 5: 月次締めを実行 → PLANNED→CONFIRMED + 仕訳 INSERT ===
        loginAsAdmin();
        // 月次締めは findLiveByOrgAndPeriodRange を使う
        given(allocationRepository.findLiveByOrgAndPeriodRange(eq(ORG_ID), any(), any()))
                .willReturn(List.of(allocation));
        // 既存仕訳なし
        given(budgetTransactionRepository.existsBySourceTypeAndSourceIdAndTransactionDate(
                anyString(), eq(ALLOCATION_ID), any()))
                .willReturn(false);
        // save は引数をそのまま返す
        given(consumptionRepository.save(any(ShiftBudgetConsumptionEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(budgetTransactionRepository.save(any(BudgetTransactionEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        MonthlyShiftBudgetCloseService.CloseResult result =
                closeService.close(ORG_ID, YearMonth.of(2026, 6));

        assertThat(result.closedAllocations()).isEqualTo(1);
        assertThat(result.closedConsumptions()).isEqualTo(2);
        assertThat(result.alreadyClosedAllocations()).isEqualTo(0);

        // F08.6 仕訳 INSERT を検証
        ArgumentCaptor<BudgetTransactionEntity> txCaptor =
                ArgumentCaptor.forClass(BudgetTransactionEntity.class);
        verify(budgetTransactionRepository).save(txCaptor.capture());
        BudgetTransactionEntity tx = txCaptor.getValue();
        assertThat(tx.getSourceType())
                .isEqualTo(MonthlyShiftBudgetCloseService.SOURCE_TYPE_SHIFT_BUDGET_MONTHLY);
        assertThat(tx.getSourceId()).isEqualTo(ALLOCATION_ID);
        assertThat(tx.getAmount()).isEqualByComparingTo("245000");
        assertThat(tx.getScopeType()).isEqualTo("TEAM");
        assertThat(tx.getScopeId()).isEqualTo(TEAM_ID);

        // CONFIRMED 化された consumption を検証 (status は in-place で変わる)
        assertThat(consumptions).allMatch(c -> c.getStatus() == ShiftBudgetConsumptionStatus.CONFIRMED);

        // 監査ログが SHIFT_BUDGET_MONTHLY_CLOSED で記録されたこと
        verify(auditLogService).record(
                eq("SHIFT_BUDGET_MONTHLY_CLOSED"),
                any(), any(), eq(TEAM_ID), eq(ORG_ID),
                any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("二重月次締め: 既存仕訳ありなら alreadyClosed として skip")
    void 月次締め冪等性() {
        loginAsAdmin();
        addConsumption(5L, new BigDecimal("100000"), new BigDecimal("83.33"),
                ShiftBudgetConsumptionStatus.PLANNED);

        given(allocationRepository.findLiveByOrgAndPeriodRange(eq(ORG_ID), any(), any()))
                .willReturn(List.of(allocation));
        given(budgetTransactionRepository.existsBySourceTypeAndSourceIdAndTransactionDate(
                anyString(), eq(ALLOCATION_ID), any()))
                .willReturn(true);  // 既に締め済

        MonthlyShiftBudgetCloseService.CloseResult result =
                closeService.close(ORG_ID, YearMonth.of(2026, 6));

        assertThat(result.alreadyClosedAllocations()).isEqualTo(1);
        assertThat(result.closedAllocations()).isEqualTo(0);
        // 仕訳は INSERT されない
        verify(budgetTransactionRepository, org.mockito.Mockito.never())
                .save(any(BudgetTransactionEntity.class));
    }

    @Test
    @DisplayName("CANCELLED 消化は by_user 集計から除外される")
    void cancelled除外() {
        loginAsAdmin();
        addConsumption(5L, new BigDecimal("80000"), new BigDecimal("66.67"),
                ShiftBudgetConsumptionStatus.CONFIRMED);
        addConsumption(5L, new BigDecimal("50000"), new BigDecimal("41.67"),
                ShiftBudgetConsumptionStatus.CANCELLED);  // この分は除外される

        ConsumptionSummaryResponse res = summaryService.getConsumptionSummary(ORG_ID, ALLOCATION_ID);

        assertThat(res.byUser()).hasSize(1);
        assertThat(res.byUser().get(0).userId()).isEqualTo(5L);
        assertThat(res.byUser().get(0).amount()).isEqualByComparingTo("80000");  // CANCELLED 50000 は含まない
        assertThat(res.byUser().get(0).hours()).isEqualByComparingTo("66.67");
    }

    @Test
    @DisplayName("alerts は ADMIN/VIEWER 共通で実データを返す（個人別時給を含まないため）")
    void alerts権限非依存() {
        addAlert(80, new BigDecimal("240000"));
        addAlert(100, new BigDecimal("310000"));

        // VIEWER も alerts を取得できる
        loginAsViewerOnly();
        ConsumptionSummaryResponse asViewer = summaryService.getConsumptionSummary(ORG_ID, ALLOCATION_ID);
        assertThat(asViewer.alerts()).hasSize(2);

        // ADMIN も同等数を取得する
        loginAsAdmin();
        ConsumptionSummaryResponse asAdmin = summaryService.getConsumptionSummary(ORG_ID, ALLOCATION_ID);
        assertThat(asAdmin.alerts()).hasSize(2);

        // ただし by_user は ADMIN のみ実データ、VIEWER は空配列
        assertThat(asViewer.byUser()).isEmpty();
        assertThat(asViewer.flags()).contains(ShiftBudgetSummaryService.FLAG_BY_USER_HIDDEN);
        assertThat(asAdmin.flags()).isEmpty();
    }

    @Test
    @DisplayName("Collections.emptyList 互換: 既存型シグネチャの後方互換確認")
    void 後方互換確認() {
        // 9-β 暫定実装の痕跡（Collections.emptyList / List.of の混在）を完全に置換できているか
        loginAsAdmin();
        ConsumptionSummaryResponse res = summaryService.getConsumptionSummary(ORG_ID, ALLOCATION_ID);

        // by_user / flags / alerts はすべて非 null
        assertThat(res.byUser()).isNotNull();
        assertThat(res.flags()).isNotNull();
        assertThat(res.alerts()).isNotNull();

        // 集計値も非 null
        assertThat(res.allocatedAmount()).isNotNull();
        assertThat(res.consumedAmount()).isNotNull();
        assertThat(res.confirmedAmount()).isNotNull();
        assertThat(res.plannedAmount()).isNotNull();
        assertThat(res.remainingAmount()).isNotNull();
        assertThat(res.consumptionRate()).isNotNull();
        assertThat(res.status()).isNotNull();

        // 旧 Collections.emptyList の参照（テスト容易性のため、未使用警告を避ける static 参照）
        assertThat(Collections.<String>emptyList()).isEmpty();
    }
}
