package com.mannschaft.app.budget.service;

import com.mannschaft.app.budget.BudgetApprovalStatus;
import com.mannschaft.app.budget.BudgetTransactionType;
import com.mannschaft.app.budget.entity.BudgetAllocationEntity;
import com.mannschaft.app.budget.entity.BudgetFiscalYearEntity;
import com.mannschaft.app.budget.entity.BudgetTransactionEntity;
import com.mannschaft.app.budget.repository.BudgetAllocationRepository;
import com.mannschaft.app.budget.repository.BudgetFiscalYearRepository;
import com.mannschaft.app.budget.repository.BudgetTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F10.1.1 / P3b Wave3: 予算ドメインの管理者レンズ向けサマリ Query Service（read-only）。
 *
 * <p>管理者レンズ「予算ウィジェット」（{@code ADMIN_TEAM_BUDGET} / {@code ADMIN_ORG_BUDGET}・設計書 02）向けに、
 * <b>現年度</b>の「配分 / 実績 / 残 / 超過カテゴリ数」を集約する。集計の定義は以下:</p>
 * <ul>
 *   <li><b>配分</b> = {@code budget_allocations.amount} の合計（{@link BudgetSummaryService} の totalBudget 相当）。</li>
 *   <li><b>実績</b> = 承認済み（APPROVED）EXPENSE 取引の合計（totalExpense 相当）。</li>
 *   <li><b>残</b> = 配分 − 実績（収入−支出の balance は流用しない・別計算）。</li>
 *   <li><b>超過カテゴリ数</b> = カテゴリ毎の「配分 − 承認済み EXPENSE 実績」が負（&lt; 0）になるカテゴリ数（閾値非依存）。</li>
 * </ul>
 *
 * <p><b>現年度の定義</b>: {@code start_date <= today AND end_date >= today}（{@code deleted_at IS NULL} は
 * Entity の {@code @SQLRestriction} で担保）。複数該当時は {@code start_date} 降順の先頭 1 件を採る。
 * 0 件のときは「当年度未設定」（{@code hasCurrentFiscalYear=false}・各数値 0・名称 null）を返し、症状を隠さない。</p>
 *
 * <p><b>IDOR 防止</b>: 全クエリの起点（現年度解決）が {@code scope_type + scope_id} を WHERE に含むため、
 * テナント越境は構造的に発生しない。認可（管理者 or 予算閲覧権限）は呼び出し側 Facade で別途張る
 * （本サービスは読み取り集計のみで認可しない）。</p>
 *
 * <p><b>原則 5 遵守</b>: budget ドメイン内の Repository のみを参照し、{@code @Transactional(readOnly=true)} は
 * ドメイン内に閉じる。</p>
 *
 * <p>設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md</p>
 */
@Service
@RequiredArgsConstructor
public class BudgetAdminSummaryQueryService {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    private final BudgetFiscalYearRepository fiscalYearRepository;
    private final BudgetAllocationRepository allocationRepository;
    private final BudgetTransactionRepository transactionRepository;

    /**
     * 指定スコープの現年度の予算サマリ（配分 / 実績 / 残 / 超過カテゴリ数）を返す。
     *
     * @param scopeType スコープ種別（"TEAM" / "ORGANIZATION"）
     * @param scopeId   スコープ ID（WHERE 必須・IDOR 防止）
     * @return 現年度の予算サマリ。現年度が無い場合は {@code hasCurrentFiscalYear=false} の未設定応答
     */
    @Transactional(readOnly = true)
    public BudgetAdminSummary summaryForScope(String scopeType, Long scopeId) {
        LocalDate today = LocalDate.now(JST);

        Optional<BudgetFiscalYearEntity> currentOpt =
                fiscalYearRepository.findCurrentByScope(scopeType, scopeId, today)
                        .stream()
                        .findFirst();

        if (currentOpt.isEmpty()) {
            // 当年度未設定: 例外でなく「未設定」を正直に返す（ウィジェットは導線のみ表示）。
            return BudgetAdminSummary.empty();
        }

        BudgetFiscalYearEntity fy = currentOpt.get();
        Long fiscalYearId = fy.getId();

        // 配分: カテゴリ毎の配分合計（同一カテゴリに複数行あっても合算する）
        Map<Long, BigDecimal> allocationByCategory = allocationRepository.findByFiscalYearId(fiscalYearId)
                .stream()
                .collect(Collectors.groupingBy(
                        BudgetAllocationEntity::getCategoryId,
                        Collectors.reducing(BigDecimal.ZERO, BudgetAllocationEntity::getAmount, BigDecimal::add)));

        BigDecimal allocation = allocationByCategory.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 実績: 承認済み EXPENSE 取引のカテゴリ毎合計
        Map<Long, BigDecimal> expenseByCategory = transactionRepository.findByFiscalYearId(fiscalYearId)
                .stream()
                .filter(t -> t.getApprovalStatus() == BudgetApprovalStatus.APPROVED)
                .filter(t -> t.getTransactionType() == BudgetTransactionType.EXPENSE)
                .collect(Collectors.groupingBy(
                        BudgetTransactionEntity::getCategoryId,
                        Collectors.reducing(BigDecimal.ZERO, BudgetTransactionEntity::getAmount, BigDecimal::add)));

        BigDecimal actual = expenseByCategory.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal remaining = allocation.subtract(actual);

        // 超過カテゴリ数: 配分があるカテゴリと実績があるカテゴリの和集合を走査し、(配分 − 実績) < 0 を数える。
        // 配分が無いのに実績だけあるカテゴリ（配分 0 − 実績 > 0 = 負）も超過として正しく数える。
        Set<Long> categoryIds = new HashSet<>();
        categoryIds.addAll(allocationByCategory.keySet());
        categoryIds.addAll(expenseByCategory.keySet());

        long overBudgetCategoryCount = categoryIds.stream()
                .filter(catId -> {
                    BigDecimal catAllocation = allocationByCategory.getOrDefault(catId, BigDecimal.ZERO);
                    BigDecimal catActual = expenseByCategory.getOrDefault(catId, BigDecimal.ZERO);
                    return catAllocation.subtract(catActual).signum() < 0;
                })
                .count();

        return new BudgetAdminSummary(
                true,
                fy.getName(),
                allocation,
                actual,
                remaining,
                overBudgetCategoryCount);
    }

    /**
     * 管理者レンズ予算サマリのドメインローカル集計。
     *
     * @param hasCurrentFiscalYear   現年度が存在するか（false のとき他フィールドはゼロ値）
     * @param fiscalYearName         現年度名（未設定時 null）
     * @param allocation             配分合計
     * @param actual                 実績合計（承認済み EXPENSE）
     * @param remaining              残（配分 − 実績）
     * @param overBudgetCategoryCount 超過カテゴリ数（カテゴリ毎の残が負のもの）
     */
    public record BudgetAdminSummary(
            boolean hasCurrentFiscalYear,
            String fiscalYearName,
            BigDecimal allocation,
            BigDecimal actual,
            BigDecimal remaining,
            long overBudgetCategoryCount) {

        /** 当年度未設定の応答（各数値 0・名称 null・hasCurrentFiscalYear=false）。 */
        public static BudgetAdminSummary empty() {
            return new BudgetAdminSummary(
                    false, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0L);
        }
    }
}
