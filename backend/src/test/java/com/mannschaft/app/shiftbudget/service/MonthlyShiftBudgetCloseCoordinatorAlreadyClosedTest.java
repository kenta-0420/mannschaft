package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.budget.service.ShiftBudgetMonthlyJournalService;
import com.mannschaft.app.shiftbudget.ShiftBudgetCloseEventState;
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

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CMP-260909-2152 Codex 検分3 P2-3: 締め済み期間の再実行で、二重計上回避のため既存 CLOSED を処理対象から除外しても、
 * 除外した締め済み対象（指定期間キー M が締め済みの割当）を already_closed として結果に集計する。
 *
 * <p>集計の単位は「指定期間キー M が既に CLOSED の割当の件数」。追いつき対象の M より前の締め済み期間は数えない
 * （再実行のたびに過去の締め済み期間の数だけ膨らむのを避ける）。締め済みの対象は Prepare・Post を一切呼ばない。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CMP-260909-2152 Codex 検分3 P2-3 締め済み期間の再実行は already_closed を集計する")
class MonthlyShiftBudgetCloseCoordinatorAlreadyClosedTest {

    private static final Long ORG_ID = 10L;
    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final YearMonth OCT = YearMonth.of(2026, 10);

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

    @InjectMocks
    private MonthlyShiftBudgetCloseCoordinator coordinator;

    @BeforeEach
    void setUp() {
        lenient().when(dateProvider.today()).thenReturn(LocalDate.of(2026, 11, 19));
        lenient().when(periodResolver.resolve(ORG_ID, OCT)).thenReturn(new ShiftBudgetClosePeriodResolver.ResolvedPeriod(
                OCT, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), 0, true));
        lenient().when(periodResolver.closingDayFunction(ORG_ID)).thenReturn(k -> 0);
        lenient().when(featureService.isEnabled(ORG_ID)).thenReturn(true);
        // 割当1: 9月〜10月（9月・10月とも締め済み）、割当2: 10月のみ（締め済み）
        List<ShiftBudgetAllocationEntity> allocations = List.of(
                allocation(1L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31)),
                allocation(2L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)));
        lenient().when(allocationRepository.findLiveByOrgStartingOnOrBefore(eq(ORG_ID), eq(LocalDate.of(2026, 10, 31))))
                .thenReturn(allocations);
        // 行のモックは stubbing の外で作る（when(...) の途中で別モックを stub すると UnfinishedStubbing になる）
        List<ShiftBudgetMonthlyCloseEventRepository.EventKeyRow> rows = List.of(
                row(1L, SEP, ShiftBudgetCloseEventState.CLOSED),
                row(1L, OCT, ShiftBudgetCloseEventState.CLOSED),
                row(2L, OCT, ShiftBudgetCloseEventState.CLOSED));
        lenient().when(eventRepository.findActiveKeys(anyList())).thenReturn(rows);
    }

    @Test
    @DisplayName("手動締め: 指定期間が全割当で締め済みなら closed=0・already=割当数（2）・failed=0、Prepare/Post を呼ばない")
    void 手動締めの再実行は締め済みを集計する() {
        MonthlyShiftBudgetCloseCoordinator.CloseResult result = coordinator.close(ORG_ID, OCT);

        assertThat(result.closedAllocations()).isZero();
        assertThat(result.alreadyClosedAllocations()).isEqualTo(2);
        assertThat(result.failedAllocations()).isZero();
        assertThat(result.closedConsumptions()).isZero();
        verifyNoInteractions(stage, journalService);
    }

    @Test
    @DisplayName("バッチ経路の1期間締め: 指定期間が締め済みなら already=割当数（2）、Prepare/Post を呼ばない")
    void バッチ経路の再実行も締め済みを集計する() {
        MonthlyShiftBudgetCloseCoordinator.CloseResult result = coordinator.closeFromBatch(ORG_ID, OCT);

        assertThat(result.closedAllocations()).isZero();
        assertThat(result.alreadyClosedAllocations()).isEqualTo(2);
        assertThat(result.failedAllocations()).isZero();
        verifyNoInteractions(stage, journalService);
    }

    private static ShiftBudgetAllocationEntity allocation(Long id, LocalDate start, LocalDate end) {
        ShiftBudgetAllocationEntity a = mock(ShiftBudgetAllocationEntity.class);
        lenient().when(a.getId()).thenReturn(id);
        lenient().when(a.getOrganizationId()).thenReturn(ORG_ID);
        lenient().when(a.getPeriodStart()).thenReturn(start);
        lenient().when(a.getPeriodEnd()).thenReturn(end);
        return a;
    }

    private static ShiftBudgetMonthlyCloseEventRepository.EventKeyRow row(Long allocationId, YearMonth key,
                                                                          ShiftBudgetCloseEventState state) {
        ShiftBudgetMonthlyCloseEventRepository.EventKeyRow r = mock(ShiftBudgetMonthlyCloseEventRepository.EventKeyRow.class);
        lenient().when(r.getAllocationId()).thenReturn(allocationId);
        lenient().when(r.getTargetMonth()).thenReturn(key.toString());
        lenient().when(r.getState()).thenReturn(state);
        return r;
    }
}
