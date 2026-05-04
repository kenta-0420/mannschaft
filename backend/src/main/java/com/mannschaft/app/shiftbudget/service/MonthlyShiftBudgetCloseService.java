package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.budget.BudgetApprovalStatus;
import com.mannschaft.app.budget.BudgetTransactionType;
import com.mannschaft.app.budget.entity.BudgetTransactionEntity;
import com.mannschaft.app.budget.repository.BudgetTransactionRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * F08.7 シフト予算 月次締め サービス（Phase 9-δ 第2段、API #11 / cron バッチ用）。
 *
 * <p>設計書 F08.7 (v1.2) §3 UC-4 / §4.6 / §6.1 #11 / §9.1 に準拠。</p>
 *
 * <p>処理フロー（allocation 単位の冪等処理）:</p>
 * <ol>
 *   <li>対象 allocation の生存 PLANNED 消化を抽出</li>
 *   <li>{@code budget_transactions} に source_type=SHIFT_BUDGET_MONTHLY の既存仕訳があれば
 *       {@link ShiftBudgetErrorCode#MONTHLY_ALREADY_CLOSED} (409) を投げる（重複防止）</li>
 *   <li>PLANNED → CONFIRMED へ一括遷移 + {@code allocation.confirmed_amount} に加算</li>
 *   <li>F08.6 {@code budget_transactions} に「月次集計」を 1 件 INSERT
 *       ({@code source_type='SHIFT_BUDGET_MONTHLY'}, {@code source_id=allocation_id})</li>
 *   <li>監査ログ {@code SHIFT_BUDGET_MONTHLY_CLOSED}</li>
 * </ol>
 *
 * <p>権限: {@code BUDGET_ADMIN}（バッチ起動時は SYSTEM 経路で権限チェックを skip）。</p>
 *
 * <p><strong>cron バッチ呼出時の注意</strong>: SecurityContext が設定されない場合、
 * {@link #closeFromBatch(Long, YearMonth)} を経由する（システム自動実行扱い）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonthlyShiftBudgetCloseService {

    /** 仕訳の source_type 識別子。 */
    public static final String SOURCE_TYPE_SHIFT_BUDGET_MONTHLY = "SHIFT_BUDGET_MONTHLY";

    private final ShiftBudgetAllocationRepository allocationRepository;
    private final ShiftBudgetConsumptionRepository consumptionRepository;
    private final BudgetTransactionRepository budgetTransactionRepository;
    private final ShiftBudgetFeatureService featureService;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;

    /**
     * API #11 経由の手動締め（{@code BUDGET_ADMIN} 権限必須）。
     *
     * <p>指定組織×指定月の生存 allocation を全部締める。</p>
     *
     * @return 締めた consumption レコード件数（合計、allocation を跨ぐ）
     */
    @Transactional(propagation = Propagation.NEVER)
    public CloseResult close(Long organizationId, YearMonth targetMonth) {
        featureService.requireEnabled(organizationId);
        requireBudgetAdmin(organizationId);
        return doCloseForOrg(organizationId, targetMonth);
    }

    /**
     * cron バッチ経由の自動締め（権限チェックなし、SecurityContext 不在を許容）。
     *
     * <p>{@link com.mannschaft.app.shiftbudget.batch.MonthlyShiftBudgetCloseBatchJob}
     * から呼び出される。フィーチャーフラグ判定は組織単位で行うため、
     * {@code featureService.isEnabled(orgId) == false} の組織は skip する。</p>
     */
    @Transactional(propagation = Propagation.NEVER)
    public CloseResult closeFromBatch(Long organizationId, YearMonth targetMonth) {
        if (!featureService.isEnabled(organizationId)) {
            log.debug("F08.7 月次締め cron: フィーチャーフラグ OFF の組織のためスキップ: orgId={}",
                    organizationId);
            return new CloseResult(0, 0, 0);
        }
        return doCloseForOrg(organizationId, targetMonth);
    }

    private CloseResult doCloseForOrg(Long organizationId, YearMonth targetMonth) {
        LocalDate monthStart = targetMonth.atDay(1);
        LocalDate monthEnd = targetMonth.atEndOfMonth();

        List<ShiftBudgetAllocationEntity> allocations =
                allocationRepository.findLiveByOrgAndPeriodRange(organizationId, monthStart, monthEnd);

        int closedAllocations = 0;
        int closedConsumptions = 0;
        int alreadyClosedAllocations = 0;
        for (ShiftBudgetAllocationEntity allocation : allocations) {
            try {
                int n = closeOneAllocation(allocation, monthEnd);
                if (n >= 0) {
                    closedAllocations++;
                    closedConsumptions += n;
                }
            } catch (BusinessException e) {
                if (ShiftBudgetErrorCode.MONTHLY_ALREADY_CLOSED.getCode().equals(e.getErrorCode().getCode())) {
                    alreadyClosedAllocations++;
                    log.info("F08.7 月次締め: allocation 既に締め済 → スキップ: allocId={}, month={}",
                            allocation.getId(), targetMonth);
                } else {
                    throw e;
                }
            }
        }

        log.info("F08.7 月次締め完了: orgId={}, month={}, closedAllocs={}, alreadyClosed={}, totalConsumptions={}",
                organizationId, targetMonth, closedAllocations, alreadyClosedAllocations, closedConsumptions);

        return new CloseResult(closedAllocations, alreadyClosedAllocations, closedConsumptions);
    }

    /**
     * 単一 allocation を締める。
     *
     * <p>独立トランザクション ({@link Propagation#REQUIRES_NEW}) で動作させ、
     * 1 つの allocation の失敗が他の allocation の締めを巻き戻さないようにする。</p>
     *
     * @return CONFIRMED 化した consumption の件数。既に締め済の場合は例外を投げる。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int closeOneAllocation(ShiftBudgetAllocationEntity allocation, LocalDate transactionDate) {
        // 重複チェック: 既に同 source の仕訳がある → 409
        boolean alreadyClosed = budgetTransactionRepository
                .existsBySourceTypeAndSourceIdAndTransactionDate(
                        SOURCE_TYPE_SHIFT_BUDGET_MONTHLY, allocation.getId(), transactionDate);
        if (alreadyClosed) {
            throw new BusinessException(ShiftBudgetErrorCode.MONTHLY_ALREADY_CLOSED);
        }

        // 当該 allocation の生存 PLANNED 消化を抽出
        List<ShiftBudgetConsumptionEntity> planned = consumptionRepository
                .findByAllocationIdAndStatusInAndDeletedAtIsNull(
                        allocation.getId(), List.of(ShiftBudgetConsumptionStatus.PLANNED));

        BigDecimal totalAmount = BigDecimal.ZERO;
        for (ShiftBudgetConsumptionEntity c : planned) {
            c.confirm();
            consumptionRepository.save(c);
            totalAmount = totalAmount.add(c.getAmount());
        }

        // allocation.confirmed_amount をアトミック加算
        if (totalAmount.signum() > 0) {
            allocationRepository.incrementConfirmedAmount(allocation.getId(), totalAmount);
        }

        // F08.6 budget_transactions に月次集計を 1 件 INSERT
        // amount = 月内 PLANNED→CONFIRMED 全件の合計
        BudgetTransactionEntity transaction = createMonthlySummaryTransaction(
                allocation, totalAmount, transactionDate);
        budgetTransactionRepository.save(transaction);

        // 監査ログ
        Long currentUserId = SecurityUtils.getCurrentUserIdOrNull();  // バッチ経由は null
        auditLogService.record(
                "SHIFT_BUDGET_MONTHLY_CLOSED",
                currentUserId, null,
                allocation.getTeamId(), allocation.getOrganizationId(),
                null, null, null,
                String.format("{\"allocation_id\":%d,\"period_start\":\"%s\",\"period_end\":\"%s\","
                                + "\"closed_consumption_count\":%d,\"total_amount\":%s}",
                        allocation.getId(), allocation.getPeriodStart(), allocation.getPeriodEnd(),
                        planned.size(), totalAmount));

        log.info("F08.7 月次締め: allocation 単位完了: allocId={}, plannedCount={}, totalAmount={}",
                allocation.getId(), planned.size(), totalAmount);

        return planned.size();
    }

    /**
     * 月次集計の F08.6 仕訳を生成する。
     *
     * <p>scope は allocation の {@code team_id} の有無で TEAM/ORGANIZATION を切替。
     * title は 「YYYY年M月 シフト人件費（自動集計）」固定。</p>
     */
    private BudgetTransactionEntity createMonthlySummaryTransaction(
            ShiftBudgetAllocationEntity allocation,
            BigDecimal totalAmount,
            LocalDate transactionDate) {

        String scopeType;
        Long scopeId;
        if (allocation.getTeamId() != null) {
            scopeType = "TEAM";
            scopeId = allocation.getTeamId();
        } else {
            scopeType = "ORGANIZATION";
            scopeId = allocation.getOrganizationId();
        }

        YearMonth ym = YearMonth.from(allocation.getPeriodStart());
        String title = String.format("%d年%d月 シフト人件費（自動集計）",
                ym.getYear(), ym.getMonthValue());

        Long recordedBy = SecurityUtils.getCurrentUserIdOrNull();
        // バッチ経由で recordedBy が null になる場合は created_by に NULL を入れる代わりに
        // システム特権（=created_by NOT NULL 制約に違反するため、代替として allocation の created_by を使う）
        if (recordedBy == null) {
            recordedBy = allocation.getCreatedBy();
        }

        return BudgetTransactionEntity.builder()
                .fiscalYearId(allocation.getFiscalYearId())
                .categoryId(allocation.getBudgetCategoryId())
                .scopeType(scopeType)
                .scopeId(scopeId)
                .transactionType(BudgetTransactionType.EXPENSE)
                .amount(totalAmount)
                .transactionDate(transactionDate)
                .title(title)
                .description("F08.7 シフト予算 月次締めバッチによる自動集計仕訳")
                .isAutoRecorded(true)
                .sourceType(SOURCE_TYPE_SHIFT_BUDGET_MONTHLY)
                .sourceId(allocation.getId())
                .approvalStatus(BudgetApprovalStatus.APPROVED)
                .recordedBy(recordedBy)
                .build();
    }

    private void requireBudgetAdmin(Long organizationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (!accessControlService.isSystemAdmin(currentUserId)
                && !hasOrgPermission(currentUserId, organizationId, "BUDGET_ADMIN")) {
            throw new BusinessException(ShiftBudgetErrorCode.BUDGET_ADMIN_REQUIRED);
        }
    }

    private boolean hasOrgPermission(Long userId, Long organizationId, String permissionName) {
        if (!accessControlService.isMember(userId, organizationId, "ORGANIZATION")) {
            return false;
        }
        try {
            accessControlService.checkPermission(userId, organizationId, "ORGANIZATION", permissionName);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    /**
     * 締め処理の結果サマリ。
     *
     * @param closedAllocations    今回新規に締めた allocation 件数
     * @param alreadyClosedAllocations 既に締め済として skip した allocation 件数
     * @param closedConsumptions   PLANNED→CONFIRMED 化した consumption 件数の総計
     */
    public record CloseResult(int closedAllocations, int alreadyClosedAllocations, int closedConsumptions) {
    }
}
