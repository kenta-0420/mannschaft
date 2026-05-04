package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.budget.entity.BudgetTransactionEntity;
import com.mannschaft.app.budget.repository.BudgetTransactionRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
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
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link MonthlyShiftBudgetCloseService} 単体テスト（Phase 9-δ 第2段、API #11 / cron バッチ）。
 *
 * <p>カバレッジ:</p>
 * <ul>
 *   <li>正常系: 生存 PLANNED → CONFIRMED 遷移 + budget_transactions INSERT</li>
 *   <li>重複実行検知: 既に締め済 → MONTHLY_ALREADY_CLOSED (409)</li>
 *   <li>cron バッチ経由: フィーチャーフラグ OFF 組織 → スキップ</li>
 *   <li>権限なし (BUDGET_ADMIN なし) → 403</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MonthlyShiftBudgetCloseService 単体テスト")
class MonthlyShiftBudgetCloseServiceTest {

    private static final Long ORG_ID = 1L;
    private static final Long ALLOCATION_ID = 42L;
    private static final Long USER_ID = 100L;
    private static final YearMonth TARGET_MONTH = YearMonth.of(2026, 6);

    @Mock
    private ShiftBudgetAllocationRepository allocationRepository;
    @Mock
    private ShiftBudgetConsumptionRepository consumptionRepository;
    @Mock
    private BudgetTransactionRepository budgetTransactionRepository;
    @Mock
    private ShiftBudgetFeatureService featureService;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private AuditLogService auditLogService;

    private MonthlyShiftBudgetCloseService service;

    @BeforeEach
    void setUp() {
        service = new MonthlyShiftBudgetCloseService(
                allocationRepository, consumptionRepository, budgetTransactionRepository,
                featureService, accessControlService, auditLogService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ShiftBudgetAllocationEntity sampleAllocation() {
        try {
            ShiftBudgetAllocationEntity alloc = ShiftBudgetAllocationEntity.builder()
                    .organizationId(ORG_ID).teamId(12L)
                    .fiscalYearId(3L).budgetCategoryId(17L)
                    .periodStart(LocalDate.of(2026, 6, 1))
                    .periodEnd(LocalDate.of(2026, 6, 30))
                    .allocatedAmount(new BigDecimal("300000"))
                    .consumedAmount(new BigDecimal("100000"))
                    .confirmedAmount(BigDecimal.ZERO)
                    .currency("JPY").createdBy(1L).version(0L)
                    .build();
            // テストでは reflection で id を注入
            java.lang.reflect.Field idField =
                    alloc.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(alloc, ALLOCATION_ID);
            return alloc;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ShiftBudgetConsumptionEntity samplePlannedConsumption(BigDecimal amount) {
        return ShiftBudgetConsumptionEntity.builder()
                .allocationId(ALLOCATION_ID)
                .shiftId(5L).slotId(1L).userId(USER_ID)
                .hourlyRateSnapshot(new BigDecimal("1200"))
                .hours(new BigDecimal("4.00"))
                .amount(amount)
                .currency("JPY")
                .status(ShiftBudgetConsumptionStatus.PLANNED)
                .recordedAt(java.time.LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("正常系: PLANNED 2件 → CONFIRMED 遷移 + budget_transactions 1件 INSERT")
    void 正常系_PLANNED_to_CONFIRMED() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        ShiftBudgetAllocationEntity alloc = sampleAllocation();
        given(allocationRepository.findLiveByOrgAndPeriodRange(eq(ORG_ID), any(), any()))
                .willReturn(List.of(alloc));
        given(budgetTransactionRepository.existsBySourceTypeAndSourceIdAndTransactionDate(
                eq(MonthlyShiftBudgetCloseService.SOURCE_TYPE_SHIFT_BUDGET_MONTHLY),
                eq(ALLOCATION_ID), any())).willReturn(false);
        given(consumptionRepository.findByAllocationIdAndStatusInAndDeletedAtIsNull(
                eq(ALLOCATION_ID), eq(List.of(ShiftBudgetConsumptionStatus.PLANNED))))
                .willReturn(List.of(
                        samplePlannedConsumption(new BigDecimal("4800")),
                        samplePlannedConsumption(new BigDecimal("5200"))));

        MonthlyShiftBudgetCloseService.CloseResult result =
                service.close(ORG_ID, TARGET_MONTH);

        assertThat(result.closedAllocations()).isEqualTo(1);
        assertThat(result.alreadyClosedAllocations()).isEqualTo(0);
        assertThat(result.closedConsumptions()).isEqualTo(2);

        // confirmed_amount を 4800+5200=10000 加算
        verify(allocationRepository, times(1)).incrementConfirmedAmount(ALLOCATION_ID, new BigDecimal("10000"));

        // budget_transactions に 1 件 INSERT
        ArgumentCaptor<BudgetTransactionEntity> txCaptor =
                ArgumentCaptor.forClass(BudgetTransactionEntity.class);
        verify(budgetTransactionRepository, times(1)).save(txCaptor.capture());
        BudgetTransactionEntity tx = txCaptor.getValue();
        assertThat(tx.getSourceType()).isEqualTo(MonthlyShiftBudgetCloseService.SOURCE_TYPE_SHIFT_BUDGET_MONTHLY);
        assertThat(tx.getSourceId()).isEqualTo(ALLOCATION_ID);
        assertThat(tx.getAmount()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(tx.getTitle()).contains("2026年6月");
        assertThat(tx.getIsAutoRecorded()).isTrue();

        // 監査ログ
        verify(auditLogService).record(eq("SHIFT_BUDGET_MONTHLY_CLOSED"),
                eq(USER_ID), any(), any(), eq(ORG_ID), any(), any(), any(), any());
    }

    @Test
    @DisplayName("重複実行: 既に締め済 → ALREADY_CLOSED 検出 + 件数 0 / alreadyClosed 1")
    void 重複実行_ALREADY_CLOSED検出() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        ShiftBudgetAllocationEntity alloc = sampleAllocation();
        given(allocationRepository.findLiveByOrgAndPeriodRange(eq(ORG_ID), any(), any()))
                .willReturn(List.of(alloc));
        given(budgetTransactionRepository.existsBySourceTypeAndSourceIdAndTransactionDate(
                eq(MonthlyShiftBudgetCloseService.SOURCE_TYPE_SHIFT_BUDGET_MONTHLY),
                eq(ALLOCATION_ID), any())).willReturn(true);

        MonthlyShiftBudgetCloseService.CloseResult result =
                service.close(ORG_ID, TARGET_MONTH);

        assertThat(result.closedAllocations()).isEqualTo(0);
        assertThat(result.alreadyClosedAllocations()).isEqualTo(1);
        assertThat(result.closedConsumptions()).isEqualTo(0);

        // INSERT も update も発生しない
        verify(budgetTransactionRepository, never()).save(any());
        verify(allocationRepository, never()).incrementConfirmedAmount(anyLong(), any());
    }

    @Test
    @DisplayName("cron バッチ経由: フィーチャーフラグ OFF 組織 → スキップ (CloseResult 0,0,0)")
    void cron_フラグOFF組織はスキップ() {
        given(featureService.isEnabled(ORG_ID)).willReturn(false);

        MonthlyShiftBudgetCloseService.CloseResult result =
                service.closeFromBatch(ORG_ID, TARGET_MONTH);

        assertThat(result.closedAllocations()).isEqualTo(0);
        assertThat(result.alreadyClosedAllocations()).isEqualTo(0);
        assertThat(result.closedConsumptions()).isEqualTo(0);
        // 何の DB アクセスも発生しない
        verify(allocationRepository, never()).findLiveByOrgAndPeriodRange(any(), any(), any());
    }

    @Test
    @DisplayName("権限なし (BUDGET_ADMIN なし) → BUDGET_ADMIN_REQUIRED (403)")
    void 権限なし() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        org.mockito.BDDMockito.willThrow(
                new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                .given(accessControlService)
                .checkPermission(USER_ID, ORG_ID, "ORGANIZATION", "BUDGET_ADMIN");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.close(ORG_ID, TARGET_MONTH))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                        .isEqualTo(ShiftBudgetErrorCode.BUDGET_ADMIN_REQUIRED.getCode()));
    }

    @Test
    @DisplayName("PLANNED 0件: allocation はあるが消化なし → tx INSERT は発生するが amount=0")
    void PLANNED0件_amount0でtx生成() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        ShiftBudgetAllocationEntity alloc = sampleAllocation();
        given(allocationRepository.findLiveByOrgAndPeriodRange(eq(ORG_ID), any(), any()))
                .willReturn(List.of(alloc));
        given(budgetTransactionRepository.existsBySourceTypeAndSourceIdAndTransactionDate(
                any(), any(), any())).willReturn(false);
        given(consumptionRepository.findByAllocationIdAndStatusInAndDeletedAtIsNull(
                eq(ALLOCATION_ID), any())).willReturn(List.of());

        MonthlyShiftBudgetCloseService.CloseResult result =
                service.close(ORG_ID, TARGET_MONTH);

        assertThat(result.closedAllocations()).isEqualTo(1);
        assertThat(result.closedConsumptions()).isEqualTo(0);

        // amount 0 なので incrementConfirmedAmount は呼ばれない（負数禁止 CHECK 制約への配慮）
        verify(allocationRepository, never()).incrementConfirmedAmount(anyLong(), any());
        // tx は 1 件作成（amount=0、冪等性のための「締め済印」）
        verify(budgetTransactionRepository, times(1)).save(any());
    }
}
