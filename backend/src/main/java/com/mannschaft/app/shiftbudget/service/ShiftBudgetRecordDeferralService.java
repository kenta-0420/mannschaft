package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.transaction.NonTransactionalEntryGuard;
import com.mannschaft.app.shift.event.ShiftPublishedEvent;
import com.mannschaft.app.shiftbudget.ShiftBudgetDeferralState;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventType;
import com.mannschaft.app.shiftbudget.UuidBytes;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetRecordDeferralEntity;
import com.mannschaft.app.shiftbudget.listener.ShiftBudgetConsumptionRecordListener;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetMonthlyCloseEventRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRecordDeferralRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 遅延記録（shift_budget_record_deferrals）の確保・延長・確定・再実行（CMP-260909-2152 確定設計 r12 §4.4）。
 *
 * <p>確保・延長・確定は DB の NOW() と所有者（claimed_by）で判定する。確保で attempt_count を +1 し、リースは30分。
 * 確定は所有者のみ（0行なら結果を破棄する）。リース切れ後は別の回収者が再確保でき、旧所有者の延長・確定は0行になる。
 * 重なりは最大で処理中の勤務枠1つ分で、その枠も割当ロックと current read で冪等に収束する。</p>
 *
 * <p>再実行は現在のスケジュール状態で分岐する: 公開中（PUBLISHED かつ未論理削除）なら整合取消のあと公開の記録を同期で実行、
 * それ以外（ARCHIVED・論理削除・公開取消）なら取消を同期で実行。結果は failed 0 かつ deferred 0 で REPLAYED、deferred ≥ 1 で
 * REDEFERRED（新イベント用の遅延行は記録・取消側が作成）、failed ≥ 1 または例外で PENDING のまま last_error を記録して所有解除。</p>
 */
@Slf4j
@Service
public class ShiftBudgetRecordDeferralService {

    private final ShiftBudgetRecordDeferralRepository deferralRepository;
    private final ShiftBudgetRateQueryRepository rateQueryRepository;
    private final ShiftBudgetConsumptionService consumptionService;
    private final ShiftBudgetLockStepHooks hooks;
    private final ObjectProvider<ShiftBudgetConsumptionRecordListener> recordListenerProvider;
    private final ShiftBudgetFailedEventService failedEventService;
    private final ShiftBudgetMonthlyCloseEventRepository eventRepository;
    private final ShiftBudgetAllocationRepository allocationRepository;
    private final Clock wallClock;

    public ShiftBudgetRecordDeferralService(ShiftBudgetRecordDeferralRepository deferralRepository,
                                            ShiftBudgetRateQueryRepository rateQueryRepository,
                                            ShiftBudgetConsumptionService consumptionService,
                                            ShiftBudgetLockStepHooks hooks,
                                            ObjectProvider<ShiftBudgetConsumptionRecordListener> recordListenerProvider,
                                            ShiftBudgetFailedEventService failedEventService,
                                            ShiftBudgetMonthlyCloseEventRepository eventRepository,
                                            ShiftBudgetAllocationRepository allocationRepository,
                                            @Qualifier("wallClock") Clock wallClock) {
        this.deferralRepository = deferralRepository;
        this.rateQueryRepository = rateQueryRepository;
        this.consumptionService = consumptionService;
        this.hooks = hooks;
        this.recordListenerProvider = recordListenerProvider;
        this.failedEventService = failedEventService;
        this.eventRepository = eventRepository;
        this.allocationRepository = allocationRepository;
        this.wallClock = wallClock;
    }

    /** 確保 → 再実行 → 確定。確保できなければ {@link ReplayOutcome#NOT_CLAIMED}、確定が0行なら {@link ReplayOutcome#DISCARDED}。 */
    public ReplayOutcome replay(UUID deferralId, String owner) {
        NonTransactionalEntryGuard.assertNoActiveTransaction();
        byte[] id = UuidBytes.toBytes(deferralId);
        if (deferralRepository.claim(id, owner) != 1) {
            return ReplayOutcome.NOT_CLAIMED;
        }
        try {
            hooks.afterDeferralClaimed(deferralId, owner);
            if (!extendLease(deferralId, owner)) {
                return ReplayOutcome.DISCARDED;
            }
            ShiftBudgetRecordDeferralEntity deferral = deferralRepository.findById(deferralId)
                    .orElseThrow(() -> new IllegalStateException("遅延記録が見つかりません: id=" + deferralId));
            Long shiftId = deferral.getShiftId();

            List<Object[]> basics = rateQueryRepository.findScheduleBasics(shiftId);
            boolean published = !basics.isEmpty() && "PUBLISHED".equals(String.valueOf(basics.get(0)[1]))
                    && !truthy(basics.get(0)[2]);
            int deferred = 0;
            boolean failed = false;
            String failureText = null;
            if (published) {
                deferred += consumptionService.cancelForShift(shiftId).deferred();
                if (!extendLease(deferralId, owner)) {
                    return ReplayOutcome.DISCARDED;
                }
                Long teamId = ((Number) basics.get(0)[0]).longValue();
                ShiftRecordOutcome outcome = recordListenerProvider.getObject()
                        .onShiftPublished(new ShiftPublishedEvent(shiftId, teamId, null, LocalDateTime.now(wallClock)));
                deferred += outcome.deferred();
                if (outcome.failed() > 0) {
                    failed = true;
                    failureText = "記録の再実行で失敗: " + outcome.failures();
                }
            } else {
                hooks.beforeReplayCancelTx(deferralId, shiftId);
                deferred += consumptionService.cancelForShift(shiftId).deferred();
            }

            if (failed) {
                deferralRepository.releaseWithError(id, owner, truncate(failureText));
                recordReplayFailure(deferral, failureText);
                return ReplayOutcome.FAILED;
            }
            ShiftBudgetDeferralState state = deferred > 0 ? ShiftBudgetDeferralState.REDEFERRED : ShiftBudgetDeferralState.REPLAYED;
            if (deferralRepository.resolve(id, owner, state.name()) != 1) {
                log.warn("F08.7 遅延記録: 確定が0行（所有喪失）のため結果を破棄: id={}, owner={}", deferralId, owner);
                return ReplayOutcome.DISCARDED;
            }
            return state == ShiftBudgetDeferralState.REDEFERRED ? ReplayOutcome.REDEFERRED : ReplayOutcome.REPLAYED;
        } catch (Exception e) {
            String text = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("F08.7 遅延記録: 再実行に失敗（PENDING のまま残し回収バッチに任せる）: id={}", deferralId, e);
            try {
                deferralRepository.releaseWithError(id, owner, truncate(text));
                deferralRepository.findById(deferralId).ifPresent(d -> recordReplayFailure(d, text));
            } catch (Exception releaseEx) {
                log.error("F08.7 遅延記録: 失敗の記録・所有解除に失敗: id={}", deferralId, releaseEx);
            }
            return ReplayOutcome.FAILED;
        }
    }

    /** リース延長。0行（所有喪失）なら false。 */
    public boolean extendLease(UUID deferralId, String owner) {
        return deferralRepository.extend(UuidBytes.toBytes(deferralId), owner) == 1;
    }

    private void recordReplayFailure(ShiftBudgetRecordDeferralEntity deferral, String text) {
        try {
            Long allocationId = eventRepository.findAllocationIdById(deferral.getCloseEventId()).orElse(null);
            if (allocationId == null) {
                return;
            }
            allocationRepository.findById(allocationId).ifPresent(a -> failedEventService.recordFailure(
                    a.getOrganizationId(), ShiftBudgetFailedEventType.MONTHLY_CLOSE, a.getId(),
                    Map.of("operation", "DEFERRAL_REPLAY_FAILED", "shift_schedule_id", deferral.getShiftId(),
                            "close_event_id", deferral.getCloseEventId().toString()),
                    text));
        } catch (Exception e) {
            log.error("F08.7 遅延記録: 失敗イベントの記録に失敗: deferralId={}", deferral.getId(), e);
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value instanceof Number n && n.longValue() != 0;
    }

    /** 再実行の結果。 */
    public enum ReplayOutcome {
        NOT_CLAIMED,
        REPLAYED,
        REDEFERRED,
        FAILED,
        DISCARDED
    }
}
