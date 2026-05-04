package com.mannschaft.app.shiftbudget.listener;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.shift.event.ShiftArchivedEvent;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetConsumptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * F08.7 シフトアーカイブ→消化記録 CANCELLED 遷移 hook（Phase 9-β）。
 *
 * <p>{@link ShiftArchivedEvent} を {@code AFTER_COMMIT} で購読し、
 * 当該シフトに紐付く PLANNED 消化を {@code CANCELLED} 遷移し、
 * {@code allocation.consumed_amount} をアトミック減算する。</p>
 *
 * <p>CONFIRMED は変更不可（取消仕訳パターンが必要だが Phase 9-β スコープ外）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftBudgetConsumptionCancelListener {

    private final ShiftBudgetFeatureService featureService;
    private final ShiftBudgetRateQueryRepository rateQueryRepository;
    private final ShiftBudgetConsumptionService consumptionService;
    private final AuditLogService auditLogService;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShiftArchived(ShiftArchivedEvent event) {
        Long scheduleId = event.getScheduleId();
        Long teamId = event.getTeamId();

        try {
            Optional<Long> orgIdOpt = rateQueryRepository.findOrganizationIdByTeamId(teamId);
            if (orgIdOpt.isEmpty()) {
                log.debug("F08.7 cancel hook: organization_id 不在のためスキップ: scheduleId={}", scheduleId);
                return;
            }
            Long organizationId = orgIdOpt.get();

            if (!featureService.isEnabled(organizationId)) {
                log.debug("F08.7 cancel hook: フィーチャーフラグ OFF のためスキップ: organizationId={}",
                        organizationId);
                return;
            }

            int cancelledCount = consumptionService.cancelAllForShift(scheduleId);

            if (cancelledCount > 0) {
                auditLogService.record(
                        "SHIFT_BUDGET_CONSUMPTION_CANCELLED",
                        event.getArchivedByUserId(), null,
                        teamId, organizationId,
                        null, null, null,
                        String.format("{\"shift_schedule_id\":%d,\"cancelled_count\":%d}",
                                scheduleId, cancelledCount));
            }
        } catch (Exception e) {
            log.error("F08.7 cancel hook: シフトアーカイブ消化キャンセルの致命的失敗: scheduleId={}", scheduleId, e);
        }
    }
}
