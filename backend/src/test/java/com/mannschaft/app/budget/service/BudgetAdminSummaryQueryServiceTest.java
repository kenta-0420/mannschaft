package com.mannschaft.app.budget.service;

import com.mannschaft.app.budget.BudgetApprovalStatus;
import com.mannschaft.app.budget.BudgetTransactionType;
import com.mannschaft.app.budget.entity.BudgetAllocationEntity;
import com.mannschaft.app.budget.entity.BudgetFiscalYearEntity;
import com.mannschaft.app.budget.entity.BudgetTransactionEntity;
import com.mannschaft.app.budget.repository.BudgetAllocationRepository;
import com.mannschaft.app.budget.repository.BudgetFiscalYearRepository;
import com.mannschaft.app.budget.repository.BudgetTransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * F10.1.1 / P3b Wave3: {@link BudgetAdminSummaryQueryService} 単体テスト。
 *
 * <p>観点:</p>
 * <ul>
 *   <li>現年度から 配分 / 実績(承認済み EXPENSE) / 残(配分−実績) / 超過カテゴリ数 を算出</li>
 *   <li>承認済み以外・EXPENSE 以外の取引は実績に数えない</li>
 *   <li>超過カテゴリ数 = カテゴリ毎の (配分 − 実績) が負のカテゴリ数（配分無し実績ありも含む）</li>
 *   <li>現年度 0 件 → 例外でなく「未設定」応答（hasCurrentFiscalYear=false）</li>
 *   <li>複数該当時は start_date 降順の先頭を採る（repository が ORDER BY 済み・先頭利用）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BudgetAdminSummaryQueryService 単体テスト")
class BudgetAdminSummaryQueryServiceTest {

    @Mock
    private BudgetFiscalYearRepository fiscalYearRepository;
    @Mock
    private BudgetAllocationRepository allocationRepository;
    @Mock
    private BudgetTransactionRepository transactionRepository;

    @InjectMocks
    private BudgetAdminSummaryQueryService service;

    private static final Long TEAM_ID = 10L;
    private static final Long FY_ID = 100L;

    private BudgetFiscalYearEntity fiscalYear(Long id, String name) {
        BudgetFiscalYearEntity fy = BudgetFiscalYearEntity.builder()
                .scopeType("TEAM")
                .scopeId(TEAM_ID)
                .name(name)
                .startDate(LocalDate.now().minusMonths(1))
                .endDate(LocalDate.now().plusMonths(11))
                .createdBy(1L)
                .build();
        // id は @GeneratedValue のため builder で設定不可 → リフレクションで詰める
        try {
            var f = com.mannschaft.app.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(fy, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return fy;
    }

    private BudgetAllocationEntity allocation(Long categoryId, String amount) {
        return BudgetAllocationEntity.builder()
                .fiscalYearId(FY_ID)
                .categoryId(categoryId)
                .amount(new BigDecimal(amount))
                .build();
    }

    private BudgetTransactionEntity expense(Long categoryId, String amount, BudgetApprovalStatus status) {
        return tx(categoryId, amount, status, BudgetTransactionType.EXPENSE);
    }

    private BudgetTransactionEntity tx(Long categoryId, String amount,
                                       BudgetApprovalStatus status, BudgetTransactionType type) {
        return BudgetTransactionEntity.builder()
                .fiscalYearId(FY_ID)
                .categoryId(categoryId)
                .scopeType("TEAM")
                .scopeId(TEAM_ID)
                .transactionType(type)
                .amount(new BigDecimal(amount))
                .transactionDate(LocalDate.now())
                .title("t")
                .approvalStatus(status)
                .recordedBy(1L)
                .build();
    }

    @Test
    @DisplayName("現年度あり → 配分/実績/残/超過カテゴリ数 を算出（承認済み EXPENSE のみ実績）")
    void summary_normal() {
        given(fiscalYearRepository.findCurrentByScope(eq("TEAM"), eq(TEAM_ID), any(LocalDate.class)))
                .willReturn(List.of(fiscalYear(FY_ID, "2026年度")));

        // カテゴリ1: 配分1000 / 承認EXPENSE600 → 残+400（超過でない）
        // カテゴリ2: 配分 500 / 承認EXPENSE800 → 残-300（超過）
        given(allocationRepository.findByFiscalYearId(FY_ID)).willReturn(List.of(
                allocation(1L, "1000"),
                allocation(2L, "500")));

        given(transactionRepository.findByFiscalYearId(FY_ID)).willReturn(List.of(
                expense(1L, "600", BudgetApprovalStatus.APPROVED),
                expense(2L, "800", BudgetApprovalStatus.APPROVED),
                // 以下は実績に数えない（未承認 EXPENSE / 承認済み INCOME）
                expense(1L, "999", BudgetApprovalStatus.PENDING_APPROVAL),
                tx(1L, "5000", BudgetApprovalStatus.APPROVED, BudgetTransactionType.INCOME)));

        var result = service.summaryForScope("TEAM", TEAM_ID);

        assertThat(result.hasCurrentFiscalYear()).isTrue();
        assertThat(result.fiscalYearName()).isEqualTo("2026年度");
        assertThat(result.allocation()).isEqualByComparingTo("1500");   // 1000 + 500
        assertThat(result.actual()).isEqualByComparingTo("1400");        // 600 + 800（承認 EXPENSE のみ）
        assertThat(result.remaining()).isEqualByComparingTo("100");      // 1500 - 1400
        assertThat(result.overBudgetCategoryCount()).isEqualTo(1L);      // カテゴリ2 のみ超過
    }

    @Test
    @DisplayName("配分が無いのに実績だけあるカテゴリも超過に数える（配分0 − 実績>0 = 負）")
    void summary_actualWithoutAllocation_isOverBudget() {
        given(fiscalYearRepository.findCurrentByScope(eq("TEAM"), eq(TEAM_ID), any(LocalDate.class)))
                .willReturn(List.of(fiscalYear(FY_ID, "2026年度")));

        given(allocationRepository.findByFiscalYearId(FY_ID)).willReturn(List.of(
                allocation(1L, "1000")));
        given(transactionRepository.findByFiscalYearId(FY_ID)).willReturn(List.of(
                expense(1L, "200", BudgetApprovalStatus.APPROVED),
                // カテゴリ9 は配分なし・承認 EXPENSE 50 → 0 - 50 = 負 → 超過
                expense(9L, "50", BudgetApprovalStatus.APPROVED)));

        var result = service.summaryForScope("TEAM", TEAM_ID);

        assertThat(result.allocation()).isEqualByComparingTo("1000");
        assertThat(result.actual()).isEqualByComparingTo("250");        // 200 + 50
        assertThat(result.remaining()).isEqualByComparingTo("750");
        assertThat(result.overBudgetCategoryCount()).isEqualTo(1L);     // カテゴリ9 のみ
    }

    @Test
    @DisplayName("現年度 0 件 → 例外でなく未設定応答（hasCurrentFiscalYear=false・各数値0）")
    void summary_noCurrentFiscalYear() {
        given(fiscalYearRepository.findCurrentByScope(eq("TEAM"), eq(TEAM_ID), any(LocalDate.class)))
                .willReturn(List.of());

        var result = service.summaryForScope("TEAM", TEAM_ID);

        assertThat(result.hasCurrentFiscalYear()).isFalse();
        assertThat(result.fiscalYearName()).isNull();
        assertThat(result.allocation()).isEqualByComparingTo("0");
        assertThat(result.actual()).isEqualByComparingTo("0");
        assertThat(result.remaining()).isEqualByComparingTo("0");
        assertThat(result.overBudgetCategoryCount()).isZero();
        // 年度が無ければ配分・取引は引かない
        verifyNoInteractions(allocationRepository);
        verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("複数該当 → repository が start_date 降順で返した先頭を採る")
    void summary_multipleCurrent_takesFirst() {
        BudgetFiscalYearEntity newer = fiscalYear(200L, "新しい年度");
        BudgetFiscalYearEntity older = fiscalYear(100L, "古い年度");
        given(fiscalYearRepository.findCurrentByScope(eq("TEAM"), eq(TEAM_ID), any(LocalDate.class)))
                .willReturn(List.of(newer, older)); // repository の ORDER BY start_date DESC を模す

        given(allocationRepository.findByFiscalYearId(200L)).willReturn(List.of());
        given(transactionRepository.findByFiscalYearId(200L)).willReturn(List.of());

        var result = service.summaryForScope("TEAM", TEAM_ID);

        assertThat(result.fiscalYearName()).isEqualTo("新しい年度");
    }
}
