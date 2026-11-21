package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.budget.dto.ShiftBudgetMonthlyPostResult;
import com.mannschaft.app.budget.service.ShiftBudgetMonthlyJournalService;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetFailedEventRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetMonthlyCloseEventRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRecordDeferralRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CMP-260909-2152 α Codex 全体検分 P2-2: 日次バッチ（closeAll）・バッチ入口（closeFromBatch）で成功した締めも
 * 監査ログ {@code SHIFT_BUDGET_MONTHLY_CLOSED}（F08.7 §9.1「月次締めバッチ完了時」）を業務 TX の外で記録する。
 *
 * <p>手動締め（API #11）の監査は Facade が記録するため、段取り役の手動入口 {@code close()} は記録しない（二重記録しない）。
 * 1件も締めなかった期間（締め済みのみ・全件失敗）は「成功した締め」ではないので記録しない。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CMP-260909-2152 α 検分 P2-2 バッチ経路の締めも SHIFT_BUDGET_MONTHLY_CLOSED を監査記録する")
class MonthlyShiftBudgetCloseCoordinatorAuditTest {

    private static final Long ORG_ID = 10L;
    private static final YearMonth OCT = YearMonth.of(2026, 10);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
    private static final ShiftBudgetClosePeriodResolver.ResolvedPeriod OCT_PERIOD =
            new ShiftBudgetClosePeriodResolver.ResolvedPeriod(OCT, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), 0, true);

    @Mock private ShiftBudgetAllocationRepository allocationRepository;
    @Mock private ShiftBudgetMonthlyCloseEventRepository eventRepository;
    @Mock private ShiftBudgetRecordDeferralRepository deferralRepository;
    @Mock private ShiftBudgetFailedEventRepository failedEventRepository;
    @Mock private ShiftBudgetClosePeriodResolver periodResolver;
    @Mock private ShiftBudgetCloseStageService stage;
    @Mock private ShiftBudgetMonthlyJournalService journalService;
    @Mock private ShiftBudgetRecordDeferralService deferralService;
    @Mock private ShiftBudgetFailedEventService failedEventService;
    @Mock private ShiftBudgetFeatureService featureService;
    @Mock private ShiftBudgetBusinessDateProvider dateProvider;
    @Mock private ShiftBudgetLockStepHooks hooks;
    @Mock private AuditLogService auditLogService;

    @InjectMocks
    private MonthlyShiftBudgetCloseCoordinator coordinator;

    private ShiftBudgetAllocationEntity allocation;

    @BeforeEach
    void setUp() {
        allocation = mock(ShiftBudgetAllocationEntity.class);
        lenient().when(allocation.getId()).thenReturn(1L);
        lenient().when(allocation.getOrganizationId()).thenReturn(ORG_ID);
        lenient().when(allocation.getPeriodStart()).thenReturn(LocalDate.of(2026, 10, 1));
        lenient().when(allocation.getPeriodEnd()).thenReturn(LocalDate.of(2026, 10, 31));
        lenient().when(dateProvider.today()).thenReturn(TODAY);
        lenient().when(featureService.isEnabled(ORG_ID)).thenReturn(true);
        lenient().when(periodResolver.closingDayFunction(ORG_ID)).thenReturn(k -> 0);
        lenient().when(periodResolver.resolve(ORG_ID, OCT)).thenReturn(OCT_PERIOD);
        lenient().when(periodResolver.ensureFixed(eq(ORG_ID), eq(OCT), any())).thenReturn(OCT_PERIOD);
        lenient().when(allocationRepository.findLiveByOrgStartingOnOrBefore(ORG_ID, OCT_PERIOD.end()))
                .thenReturn(List.of(allocation));
        lenient().when(eventRepository.findDailyCandidateAllocationIds(TODAY.minusDays(1))).thenReturn(List.of(1L));
        lenient().when(allocationRepository.findAllById(List.of(1L))).thenReturn(List.of(allocation));
    }

    private void stubSuccessfulClose() {
        when(stage.prepare(1L, OCT)).thenReturn(new ShiftBudgetCloseStageService.PrepareResult(
                UUID.randomUUID(), 3, new BigDecimal("12000.00"), LocalDate.of(2026, 10, 31)));
        when(journalService.post(any())).thenReturn(
                new ShiftBudgetMonthlyPostResult(ShiftBudgetMonthlyPostResult.Outcome.POSTED, 99L, null));
    }

    @Test
    @DisplayName("日次バッチ closeAll で締めた組織×期間は SHIFT_BUDGET_MONTHLY_CLOSED を1回記録する（操作者なし・期間付き）")
    void 日次バッチの成功を監査記録する() {
        stubSuccessfulClose();

        MonthlyShiftBudgetCloseCoordinator.DailyCloseResult result = coordinator.closeAll(TODAY);

        assertThat(result.closedAllocations()).isEqualTo(1);
        verify(auditLogService, times(1)).record(eq("SHIFT_BUDGET_MONTHLY_CLOSED"), isNull(), isNull(), any(),
                eq(ORG_ID), isNull(), isNull(), isNull(), contains("\"period_key\":\"2026-10\""));
    }

    @Test
    @DisplayName("バッチ入口 closeFromBatch で締めた期間も SHIFT_BUDGET_MONTHLY_CLOSED を1回記録する")
    void バッチ入口の成功を監査記録する() {
        stubSuccessfulClose();

        MonthlyShiftBudgetCloseCoordinator.CloseResult result = coordinator.closeFromBatch(ORG_ID, OCT);

        assertThat(result.closedAllocations()).isEqualTo(1);
        verify(auditLogService, times(1)).record(eq("SHIFT_BUDGET_MONTHLY_CLOSED"), isNull(), isNull(), any(),
                eq(ORG_ID), isNull(), isNull(), isNull(), contains("\"period_end\":\"2026-10-31\""));
    }

    @Test
    @DisplayName("手動入口 close() は記録しない（Facade が記録するため二重記録しない）")
    void 手動入口は段取り役で記録しない() {
        stubSuccessfulClose();

        coordinator.close(ORG_ID, OCT);

        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("1件も締めなかった（全件中止）バッチは成功した締めではないので記録しない")
    void 全件中止は記録しない() {
        when(stage.prepare(1L, OCT)).thenReturn(new ShiftBudgetCloseStageService.PrepareResult(
                UUID.randomUUID(), 3, new BigDecimal("12000.00"), LocalDate.of(2026, 10, 31)));
        when(journalService.post(any())).thenReturn(
                new ShiftBudgetMonthlyPostResult(ShiftBudgetMonthlyPostResult.Outcome.ABORTED, null, "FY_CLOSED"));

        coordinator.closeAll(TODAY);

        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
