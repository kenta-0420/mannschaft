package com.mannschaft.app.shiftbudget.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.shift.event.ShiftArchivedEvent;
import com.mannschaft.app.shift.event.ShiftScheduleClosedEvent;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventType;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetConsumptionService;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetFailedEventService;
import com.mannschaft.app.shiftbudget.service.ThresholdAlertEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashSet;
import java.util.Map;
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
    /** Phase 10-β で追加: 失敗イベントの永続化（リトライバッチ + 管理 API の入口） */
    private final ShiftBudgetFailedEventService failedEventService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "殿の裁定: 記録と取消は対であり、片方だけ止まれば予算残高が壊れる。対になっているものを別々に扱ってはならず、いずれも常時実行とする")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShiftArchived(ShiftArchivedEvent event) {
        cancelConsumptions(event.getScheduleId(), event.getTeamId(), event.getArchivedByUserId());
    }

    /**
     * CMP-260909-1445: アーカイブ以外で公開が失効した経路（論理削除・公開取消）でも消化を取り消す。
     *
     * <p>是正前は ARCHIVED しか購読しておらず、シフトを削除しても公開を取り消しても
     * PLANNED 消化が残り続け、当該 allocation が {@code SHIFT_BUDGET_012} で恒久的に
     * 削除不能になっていた（殿の実機で実際に割当 3 件が削除不能になった）。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "ShiftArchivedEvent の購読と同じ理由。記録と取消は対であり、取消側だけ止まれば予算残高が壊れて割当が恒久的に削除不能になる")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShiftScheduleClosed(ShiftScheduleClosedEvent event) {
        cancelConsumptions(event.getScheduleId(), event.getTeamId(), event.getActorUserId());
    }

    /**
     * 指定シフトの PLANNED 消化を取り消し、{@code allocation.consumed_amount} を減算する。
     *
     * <p>{@code ShiftBudgetConsumptionService#cancelAllForShift} は PLANNED のみを対象とするため、
     * 同じシフトに対して複数回呼ばれても二重減算は起きない（冪等）。</p>
     *
     * @param actorUserId 操作者ID。バッチ等の自動処理では null
     */
    private void cancelConsumptions(Long scheduleId, Long teamId, Long actorUserId) {
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
                        actorUserId, null,
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
                        // Phase 10-β: 失敗イベントとして永続化
                        recordFailureSafe(organizationId,
                                ShiftBudgetFailedEventType.THRESHOLD_ALERT,
                                allocationId,
                                Map.of(
                                        "allocation_id", allocationId,
                                        "shift_schedule_id", scheduleId,
                                        "operation", "CANCEL_RE_EVALUATE"
                                ),
                                thresholdEx);
                    }
                }
            }
        } catch (Exception e) {
            log.error("F08.7 cancel hook: シフトアーカイブ消化キャンセルの致命的失敗: scheduleId={}", scheduleId, e);
            // Phase 10-β: organization_id が解決できていれば failed_events にも記録
            try {
                Long orgIdForFailure = rateQueryRepository.findOrganizationIdByTeamId(teamId)
                        .orElse(null);
                if (orgIdForFailure != null) {
                    recordFailureSafe(orgIdForFailure,
                            ShiftBudgetFailedEventType.CONSUMPTION_CANCEL,
                            scheduleId,
                            Map.of(
                                    "shift_schedule_id", scheduleId,
                                    "team_id", teamId,
                                    "archived_by_user_id",
                                    actorUserId == null ? -1L : actorUserId
                            ),
                            e);
                }
            } catch (Exception ignore) {
                // failed_events 記録自体の失敗も諦める（ERROR ログは既に出ている）
            }
        }
    }

    /**
     * Phase 10-β: 失敗イベント記録を例外安全に呼ぶ。
     */
    private void recordFailureSafe(Long organizationId, ShiftBudgetFailedEventType eventType,
                                   Long sourceId, Map<String, Object> payload, Throwable cause) {
        try {
            String errorMsg = cause.getClass().getSimpleName() + ": " + cause.getMessage();
            failedEventService.recordFailure(organizationId, eventType, sourceId, payload, errorMsg);
        } catch (Exception recEx) {
            log.error("F08.7 cancel hook: failed_events 記録自体も失敗（諦め）: orgId={}, eventType={}",
                    organizationId, eventType, recEx);
        }
    }
}
