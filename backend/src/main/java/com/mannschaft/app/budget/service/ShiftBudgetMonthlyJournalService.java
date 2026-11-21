package com.mannschaft.app.budget.service;

import com.mannschaft.app.budget.BudgetApprovalStatus;
import com.mannschaft.app.budget.BudgetFiscalYearStatus;
import com.mannschaft.app.budget.BudgetTransactionType;
import com.mannschaft.app.budget.dto.ShiftBudgetMonthlyPostCommand;
import com.mannschaft.app.budget.dto.ShiftBudgetMonthlyPostResult;
import com.mannschaft.app.budget.entity.BudgetFiscalYearEntity;
import com.mannschaft.app.budget.entity.BudgetShiftClosePostingEntity;
import com.mannschaft.app.budget.entity.BudgetTransactionEntity;
import com.mannschaft.app.budget.repository.BudgetFiscalYearRepository;
import com.mannschaft.app.budget.repository.BudgetShiftClosePostingRepository;
import com.mannschaft.app.budget.repository.BudgetTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 3段締めの段2 Post（budget TX。CMP-260909-2152 確定設計 r12 §3）。
 *
 * <p>年度行 FOR UPDATE → 投稿判定行 FOR UPDATE の順に取る。判定行があれば年度状態に関係なく保存済み判定を返す。
 * 無ければ、年度 OPEN で仕訳＋判定 POSTED、CLOSED で判定 ABORTED(FY_CLOSED)、同割当同仕訳日の生存仕訳があれば
 * 判定 ABORTED(JOURNAL_CONFLICT) を、同じ TX で INSERT する。年度ロックと判定行の UNIQUE により、投稿／中止は
 * 1イベントにつき1回だけ確定し、後発は保存済み判定を受け取る。</p>
 *
 * <p>shiftbudget のテーブルには触れない（ロックは budget ドメインの中で完結し、shiftbudget のロックと同一 TX で取らない）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftBudgetMonthlyJournalService {

    /** 仕訳の source_type 識別子（旧締めと同じ）。 */
    public static final String SOURCE_TYPE_SHIFT_BUDGET_MONTHLY = "SHIFT_BUDGET_MONTHLY";

    private final BudgetFiscalYearRepository fiscalYearRepository;
    private final BudgetTransactionRepository transactionRepository;
    private final BudgetShiftClosePostingRepository postingRepository;

    /** 段2 Post。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ShiftBudgetMonthlyPostResult post(ShiftBudgetMonthlyPostCommand command) {
        BudgetFiscalYearEntity fiscalYear = fiscalYearRepository.findByIdForUpdate(command.fiscalYearId())
                .orElseThrow(() -> new IllegalStateException("年度が見つかりません: id=" + command.fiscalYearId()));

        Optional<BudgetShiftClosePostingEntity> saved = postingRepository.findByCloseEventIdForUpdate(command.closeEventId());
        if (saved.isPresent()) {
            return toResult(saved.get());
        }

        if (fiscalYear.getStatus() == BudgetFiscalYearStatus.CLOSED) {
            return abort(command, BudgetShiftClosePostingEntity.REASON_FY_CLOSED);
        }
        if (transactionRepository.existsBySourceTypeAndSourceIdAndTransactionDate(
                SOURCE_TYPE_SHIFT_BUDGET_MONTHLY, command.allocationId(), command.journalDate())) {
            return abort(command, BudgetShiftClosePostingEntity.REASON_JOURNAL_CONFLICT);
        }

        BudgetTransactionEntity journal = transactionRepository.save(BudgetTransactionEntity.builder()
                .fiscalYearId(command.fiscalYearId())
                .categoryId(command.categoryId())
                .scopeType(command.scopeType())
                .scopeId(command.scopeId())
                .transactionType(BudgetTransactionType.EXPENSE)
                .amount(command.amount())
                .transactionDate(command.journalDate())
                .title(command.periodStart() + "〜" + command.periodEnd() + " シフト人件費（自動集計）")
                .description("F08.7 シフト予算 期間締めによる自動集計仕訳")
                .isAutoRecorded(true)
                .sourceType(SOURCE_TYPE_SHIFT_BUDGET_MONTHLY)
                .sourceId(command.allocationId())
                .approvalStatus(BudgetApprovalStatus.APPROVED)
                .recordedBy(command.recordedBy())
                .build());
        postingRepository.saveAndFlush(BudgetShiftClosePostingEntity.builder()
                .closeEventId(command.closeEventId())
                .allocationId(command.allocationId())
                .fiscalYearId(command.fiscalYearId())
                .outcome(BudgetShiftClosePostingEntity.OUTCOME_POSTED)
                .journalTransactionId(journal.getId())
                .build());
        log.info("F08.7 期間締め Post: 仕訳を投稿: allocationId={}, eventId={}, journalId={}, amount={}",
                command.allocationId(), command.closeEventId(), journal.getId(), command.amount());
        return new ShiftBudgetMonthlyPostResult(ShiftBudgetMonthlyPostResult.Outcome.POSTED, journal.getId(), null);
    }

    private ShiftBudgetMonthlyPostResult abort(ShiftBudgetMonthlyPostCommand command, String reason) {
        postingRepository.saveAndFlush(BudgetShiftClosePostingEntity.builder()
                .closeEventId(command.closeEventId())
                .allocationId(command.allocationId())
                .fiscalYearId(command.fiscalYearId())
                .outcome(BudgetShiftClosePostingEntity.OUTCOME_ABORTED)
                .reason(reason)
                .build());
        log.warn("F08.7 期間締め Post: 中止を確定: allocationId={}, eventId={}, reason={}",
                command.allocationId(), command.closeEventId(), reason);
        return new ShiftBudgetMonthlyPostResult(ShiftBudgetMonthlyPostResult.Outcome.ABORTED, null, reason);
    }

    private static ShiftBudgetMonthlyPostResult toResult(BudgetShiftClosePostingEntity posting) {
        return BudgetShiftClosePostingEntity.OUTCOME_POSTED.equals(posting.getOutcome())
                ? new ShiftBudgetMonthlyPostResult(ShiftBudgetMonthlyPostResult.Outcome.POSTED,
                        posting.getJournalTransactionId(), null)
                : new ShiftBudgetMonthlyPostResult(ShiftBudgetMonthlyPostResult.Outcome.ABORTED, null, posting.getReason());
    }
}
