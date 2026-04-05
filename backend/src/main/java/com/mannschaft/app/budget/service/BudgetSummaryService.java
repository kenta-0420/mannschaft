package com.mannschaft.app.budget.service;

import com.mannschaft.app.budget.BudgetApprovalStatus;
import com.mannschaft.app.budget.BudgetTransactionType;
import com.mannschaft.app.budget.BudgetWarningLevel;
import com.mannschaft.app.budget.dto.BudgetSummaryResponse;
import com.mannschaft.app.budget.dto.CategorySummaryResponse;
import com.mannschaft.app.budget.entity.BudgetAllocationEntity;
import com.mannschaft.app.budget.entity.BudgetConfigEntity;
import com.mannschaft.app.budget.entity.BudgetFiscalYearEntity;
import com.mannschaft.app.budget.entity.BudgetTransactionEntity;
import com.mannschaft.app.budget.repository.BudgetAllocationRepository;
import com.mannschaft.app.budget.repository.BudgetConfigRepository;
import com.mannschaft.app.budget.repository.BudgetTransactionRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 予算サマリサービス。消化率計算・警告レベル判定を担当��る。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BudgetSummaryService {

    private final BudgetTransactionRepository transactionRepository;
    private final BudgetAllocationRepository allocationRepository;
    private final BudgetConfigRepository configRepository;
    private final BudgetFiscalYearService fiscalYearService;
    private final BudgetCategoryService categoryService;
    private final AccessControlService accessControlService;

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal DEFAULT_WARNING_PERCENT = new BigDecimal("80");
    private static final BigDecimal DEFAULT_CRITICAL_PERCENT = new BigDecimal("95");

    /**
     * 会���年度の予算サマリを取得する。
     */
    public BudgetSummaryResponse getFiscalYearSummary(Long fiscalYearId) {
        BudgetFiscalYearEntity fy = fiscalYearService.findById(fiscalYearId);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(currentUserId, fy.getScopeId(), fy.getScopeType());

        // 承認済み取引のみ集計
        List<BudgetTransactionEntity> transactions = transactionRepository.findByFiscalYearId(fiscalYearId)
                .stream()
                .filter(t -> t.getApprovalStatus() == BudgetApprovalStatus.APPROVED)
                .toList();

        BigDecimal totalIncome = sumByType(transactions, BudgetTransactionType.INCOME);
        BigDecimal totalExpense = sumByType(transactions, BudgetTransactionType.EXPENSE);

        // カテゴリの予算額合計（配分から計算）
        Map<Long, BigDecimal> allocationByCategory = allocationRepository.findByFiscalYearId(fiscalYearId)
                .stream()
                .collect(Collectors.groupingBy(
                        BudgetAllocationEntity::getCategoryId,
                        Collectors.reducing(BigDecimal.ZERO, BudgetAllocationEntity::getAmount, BigDecimal::add)));

        BigDecimal totalBudget = allocationByCategory.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal balance = totalIncome.subtract(totalExpense);
        BigDecimal executionRate = totalBudget.compareTo(BigDecimal.ZERO) > 0
                ? totalExpense.multiply(HUNDRED).divide(totalBudget, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // 警告レベル判定
        BigDecimal warningThreshold = getWarningThreshold(fy.getScopeId(), fy.getScopeType());
        BigDecimal criticalThreshold = getCriticalThreshold(fy.getScopeId(), fy.getScopeType());
        String warningLevel = determineWarningLevel(executionRate, warningThreshold, criticalThreshold);

        // カテゴリ別サマリ
        List<CategorySummaryResponse> categorySummaries = buildCategorySummaries(
                fiscalYearId, transactions, allocationByCategory, warningThreshold, criticalThreshold);

        return new BudgetSummaryResponse(
                fy.getId(),
                fy.getName(),
                totalBudget,
                totalIncome,
                totalExpense,
                balance,
                executionRate,
                warningLevel,
                categorySummaries
        );
    }

    /**
     * カテゴリ別サマリを取得する。
     */
    public CategorySummaryResponse getCategorySummary(Long categoryId, Long fiscalYearId) {
        BudgetFiscalYearEntity fy = fiscalYearService.findById(fiscalYearId);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(currentUserId, fy.getScopeId(), fy.getScopeType());

        var category = categoryService.findById(categoryId);

        List<BudgetTransactionEntity> transactions = transactionRepository.findByFiscalYearId(fiscalYearId)
                .stream()
                .filter(t -> t.getCategoryId().equals(categoryId))
                .filter(t -> t.getApprovalStatus() == BudgetApprovalStatus.APPROVED)
                .toList();

        BigDecimal actualAmount = transactions.stream()
                .map(BudgetTransactionEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 配分合計を予算額として使用
        BigDecimal budget = allocationRepository.findByFiscalYearId(fiscalYearId).stream()
                .filter(a -> a.getCategoryId().equals(categoryId))
                .map(BudgetAllocationEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal remaining = budget.subtract(actualAmount);
        BigDecimal executionRate = budget.compareTo(BigDecimal.ZERO) > 0
                ? actualAmount.multiply(HUNDRED).divide(budget, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal warningThreshold = getWarningThreshold(fy.getScopeId(), fy.getScopeType());
        BigDecimal criticalThreshold = getCriticalThreshold(fy.getScopeId(), fy.getScopeType());

        return new CategorySummaryResponse(
                category.getId(),
                category.getName(),
                category.getCategoryType().name(),
                budget,
                actualAmount,
                remaining,
                executionRate,
                determineWarningLevel(executionRate, warningThreshold, criticalThreshold)
        );
    }

    // ========================================
    // ��ルパー
    // ========================================

    private BigDecimal sumByType(List<BudgetTransactionEntity> transactions, BudgetTransactionType type) {
        return transactions.stream()
                .filter(t -> t.getTransactionType() == type)
                .map(BudgetTransactionEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<CategorySummaryResponse> buildCategorySummaries(
            Long fiscalYearId,
            List<BudgetTransactionEntity> transactions,
            Map<Long, BigDecimal> allocationByCategory,
            BigDecimal warningThreshold,
            BigDecimal criticalThreshold) {

        Map<Long, List<BudgetTransactionEntity>> txByCategory = transactions.stream()
                .collect(Collectors.groupingBy(BudgetTransactionEntity::getCategoryId));

        var categories = categoryService.listFlatByFiscalYear(fiscalYearId);

        return categories.stream()
                .map(cat -> {
                    List<BudgetTransactionEntity> catTx = txByCategory.getOrDefault(cat.id(), List.of());
                    BigDecimal actual = catTx.stream()
                            .map(BudgetTransactionEntity::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal budget = allocationByCategory.getOrDefault(cat.id(), BigDecimal.ZERO);
                    BigDecimal remaining = budget.subtract(actual);
                    BigDecimal rate = budget.compareTo(BigDecimal.ZERO) > 0
                            ? actual.multiply(HUNDRED).divide(budget, 2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;

                    return new CategorySummaryResponse(
                            cat.id(), cat.name(), cat.categoryType(),
                            budget, actual, remaining, rate,
                            determineWarningLevel(rate, warningThreshold, criticalThreshold));
                })
                .toList();
    }

    private String determineWarningLevel(BigDecimal executionRate,
                                          BigDecimal warningThreshold,
                                          BigDecimal criticalThreshold) {
        if (executionRate.compareTo(criticalThreshold) >= 0) {
            return BudgetWarningLevel.CRITICAL.name();
        } else if (executionRate.compareTo(warningThreshold) >= 0) {
            return BudgetWarningLevel.WARNING.name();
        }
        return BudgetWarningLevel.NORMAL.name();
    }

    private BigDecimal getWarningThreshold(Long scopeId, String scopeType) {
        return configRepository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .map(c -> BigDecimal.valueOf(c.getBudgetWarningThreshold()))
                .orElse(DEFAULT_WARNING_PERCENT);
    }

    private BigDecimal getCriticalThreshold(Long scopeId, String scopeType) {
        return configRepository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .map(c -> BigDecimal.valueOf(c.getBudgetCriticalThreshold()))
                .orElse(DEFAULT_CRITICAL_PERCENT);
    }
}
