package com.mannschaft.app.shiftbudget.listener;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.shift.event.ShiftArchivedEvent;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetConsumptionService;
import com.mannschaft.app.shiftbudget.service.ThresholdAlertEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

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
    private final ShiftBudgetConsumptionRepository consumptionRepository;
    private final AuditLogService auditLogService;
    /** Phase 9-δ で追加: 閾値判定 hook */
    private final ThresholdAlertEvaluationService thresholdAlertEvaluationService;

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

            // CANCEL 前に当該シフトに紐付く allocation_id 集合を採取
            // (cancel 後は consumption.status=CANCELLED に遷移するが allocation_id 自体は変わらないため、
            //  事前/事後どちらでも採取可能。明示的に事前採取して意図を明らかにする)
            Set<Long> affectedAllocationIds = new HashSet<>();
            consumptionRepository.findByShiftIdAndDeletedAtIsNull(scheduleId)
                    .forEach(c -> affectedAllocationIds.add(c.getAllocationId()));

            int cancelledCount = consumptionService.cancelAllForShift(scheduleId);

            if (cancelledCount > 0) {
                auditLogService.record(
                        "SHIFT_BUDGET_CONSUMPTION_CANCELLED",
                        event.getArchivedByUserId(), null,
                        teamId, organizationId,
                        null, null, null,
                        String.format("{\"shift_schedule_id\":%d,\"cancelled_count\":%d}",
                                scheduleId, cancelledCount));

                // Phase 9-δ 追加: CANCEL 後に影響 allocation の閾値判定を再評価する。
                // 例外は飲み込む（既存パターン踏襲: hook 失敗が main トランザクションを巻き戻さないため）
                for (Long allocationId : affectedAllocationIds) {
                    try {
                        thresholdAlertEvaluationService.evaluateAndTrigger(allocationId);
                    } catch (Exception thresholdEx) {
                        log.error("F08.7 cancel hook: 閾値判定失敗（処理継続）: allocId={}, scheduleId={}",
                                allocationId, scheduleId, thresholdEx);
                    }
                }
            }
        } catch (Exception e) {
            log.error("F08.7 cancel hook: シフトアーカイブ消化キャンセルの致命的失敗: scheduleId={}", scheduleId, e);
        }
    }
}
