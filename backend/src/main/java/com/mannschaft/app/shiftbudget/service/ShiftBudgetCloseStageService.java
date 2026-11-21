package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shiftbudget.ShiftBudgetCancelReason;
import com.mannschaft.app.shiftbudget.ShiftBudgetCloseEventOrigin;
import com.mannschaft.app.shiftbudget.ShiftBudgetCloseEventState;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventType;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetClosePeriodEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetMonthlyCloseEventEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetClosePeriodRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetMonthlyCloseEventRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 3段締めの shiftbudget 側の段（Prepare／Commit／Abort。CMP-260909-2152 確定設計 r12 §3）。
 *
 * <p>各段は shiftbudget の Repository のみに到達する独立 TX。budget のロックを同一 TX で取らない。
 * ロック順は 割当 → 消化 → 締めイベント（§6）。判定（POSTED／ABORTED）を伴わない Abort 経路は持たない。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftBudgetCloseStageService {

    private final ShiftBudgetAllocationRepository allocationRepository;
    private final ShiftBudgetConsumptionRepository consumptionRepository;
    private final ShiftBudgetMonthlyCloseEventRepository eventRepository;
    private final ShiftBudgetClosePeriodRepository periodRepository;
    private final ShiftBudgetRateQueryRepository rateQueryRepository;
    private final ShiftBudgetFailedEventService failedEventService;
    private final ShiftBudgetLockStepHooks hooks;

    /**
     * 段1 Prepare。割当 FOR UPDATE → 有効イベント（CLOSED なら 409 SHIFT_BUDGET_028、PREPARED なら再開）→ 勤務枠の現在日付へ追従 →
     * work_date が期間内かつ印なしの PLANNED を FOR UPDATE → イベント PREPARED INSERT・対象行に締めイベントの印（状態は PLANNED のまま）。
     *
     * @return Prepare の結果。割当が消滅・論理削除されていれば null（締めない。イベント・印は作らない）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PrepareResult prepare(Long allocationId, YearMonth periodKey) {
        ShiftBudgetAllocationEntity allocation = allocationRepository.findByIdForUpdate(allocationId).orElse(null);
        if (allocation == null || allocation.getDeletedAt() != null) {
            log.info("F08.7 期間締め Prepare: 割当が消滅・論理削除されているためスキップ: allocId={}", allocationId);
            return null;
        }
        ShiftBudgetClosePeriodEntity period = periodRepository
                .findByOrganizationIdAndPeriodKey(allocation.getOrganizationId(), periodKey.toString())
                .orElseThrow(() -> new IllegalStateException("締め期間が未確定です: orgId=" + allocation.getOrganizationId()
                        + ", key=" + periodKey));

        ShiftBudgetMonthlyCloseEventEntity active = eventRepository
                .findActiveForUpdate(allocationId, periodKey.toString()).orElse(null);
        if (active != null) {
            if (active.getState() == ShiftBudgetCloseEventState.CLOSED) {
                throw new BusinessException(ShiftBudgetErrorCode.MONTHLY_ALREADY_CLOSED);
            }
            return new PrepareResult(active.getId(), active.getLineCount(), active.getPlannedTotal(),
                    active.getJournalDate());
        }

        List<ShiftBudgetConsumptionEntity> unmarked = consumptionRepository.lockUnmarkedPlanned(allocationId);
        Map<Long, LocalDate> slotDates = slotDates(unmarked);
        List<ShiftBudgetConsumptionEntity> targets = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ShiftBudgetConsumptionEntity row : unmarked) {
            LocalDate current = slotDates.get(row.getSlotId());
            if (current == null) {
                // 枠なし → 整合バッチ（CMP-260909-1445）に任せ、締め対象外
                continue;
            }
            if (!current.equals(row.getWorkDate())) {
                if (current.isBefore(allocation.getPeriodStart()) || current.isAfter(allocation.getPeriodEnd())) {
                    cancelMoved(allocation, row, ShiftBudgetCancelReason.SLOT_MOVED_OUT_OF_ALLOCATION, periodKey, current, true);
                    continue;
                }
                if (!eventRepository.findCoveringDate(allocationId, current,
                        List.of(ShiftBudgetCloseEventState.CLOSED)).isEmpty()) {
                    cancelMoved(allocation, row, ShiftBudgetCancelReason.SLOT_MOVED_TO_CLOSED_PERIOD, periodKey, current, false);
                    continue;
                }
                row.moveWorkDate(current);
                consumptionRepository.save(row);
            }
            LocalDate workDate = row.getWorkDate();
            if (!workDate.isBefore(period.getPeriodStart()) && !workDate.isAfter(period.getPeriodEnd())) {
                targets.add(row);
                total = total.add(row.getAmount());
            }
        }

        LocalDate journalDate = ShiftBudgetClosePeriodCalculator.journalDate(
                new ShiftBudgetClosePeriodCalculator.ClosePeriod(periodKey, period.getPeriodStart(),
                        period.getPeriodEnd(), period.getClosingDay()),
                allocation.getPeriodEnd());
        ShiftBudgetCloseActorContext.Actor actor = ShiftBudgetCloseActorContext.current();
        ShiftBudgetMonthlyCloseEventEntity event = eventRepository.saveAndFlush(ShiftBudgetMonthlyCloseEventEntity.builder()
                .allocationId(allocationId)
                .targetMonth(periodKey.toString())
                .closePeriodId(period.getId())
                .journalDate(journalDate)
                .state(ShiftBudgetCloseEventState.PREPARED)
                .origin(ShiftBudgetCloseEventOrigin.CLOSE)
                .lineCount(targets.size())
                .plannedTotal(total)
                .actorKind(actor.kind())
                .actorUserId(actor.userId())
                .preparedAt(Instant.now())
                .build());
        if (!targets.isEmpty()) {
            consumptionRepository.markCloseEvent(targets.stream().map(ShiftBudgetConsumptionEntity::getId).toList(),
                    event.getId());
        }
        hooks.afterPrepareMarked(allocationId, event.getId());
        return new PrepareResult(event.getId(), targets.size(), total, journalDate);
    }

    /**
     * 段3a Commit（判定 POSTED のみ）。冪等（CLOSED なら何もしない）。件数・合計が Prepare 時と一致しなければ TX ロールバック
     * （{@link ShiftBudgetCloseMismatchException}）。一致すれば印付き PLANNED を CONFIRMED にして確定額を加算し、イベントを CLOSED にする。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void commit(UUID closeEventId, Long journalTransactionId) {
        Long allocationId = eventRepository.findAllocationIdById(closeEventId)
                .orElseThrow(() -> new IllegalStateException("締めイベントが見つかりません: id=" + closeEventId));
        allocationRepository.findByIdForUpdate(allocationId)
                .orElseThrow(() -> new IllegalStateException("割当が見つかりません: id=" + allocationId));
        ShiftBudgetMonthlyCloseEventEntity event = eventRepository.findByIdForUpdate(closeEventId)
                .orElseThrow(() -> new IllegalStateException("締めイベントが見つかりません: id=" + closeEventId));
        if (event.getState() == ShiftBudgetCloseEventState.CLOSED) {
            return;
        }
        if (event.getState() != ShiftBudgetCloseEventState.PREPARED) {
            throw new IllegalStateException("PREPARED でないイベントは Commit できません: id=" + closeEventId
                    + ", state=" + event.getState());
        }
        if (event.getOrigin() == ShiftBudgetCloseEventOrigin.CLOSE) {
            List<ShiftBudgetConsumptionEntity> marked = consumptionRepository.lockMarkedPlanned(closeEventId);
            BigDecimal sum = BigDecimal.ZERO;
            for (ShiftBudgetConsumptionEntity row : marked) {
                sum = sum.add(row.getAmount());
            }
            if (marked.size() != event.getLineCount() || sum.compareTo(event.getPlannedTotal()) != 0) {
                throw new ShiftBudgetCloseMismatchException("Commit の件数・合計が Prepare 時と一致しません: eventId=" + closeEventId
                        + ", prepared=" + event.getLineCount() + "/" + event.getPlannedTotal()
                        + ", actual=" + marked.size() + "/" + sum);
            }
            for (ShiftBudgetConsumptionEntity row : marked) {
                row.confirm();
                consumptionRepository.save(row);
            }
            if (sum.signum() > 0) {
                allocationRepository.incrementConfirmedAmount(allocationId, sum);
            }
        }
        event.markClosed(journalTransactionId);
        eventRepository.save(event);
    }

    /**
     * 段3b Abort（判定 ABORTED のみ）。PREPARED なら印を外し、イベントを ABORTED にする（履歴として残す）。
     * 判定前の Abort や利用者操作の Abort 経路は無い（呼び出しは段取り役のみ）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abort(UUID closeEventId, String reason) {
        Long allocationId = eventRepository.findAllocationIdById(closeEventId)
                .orElseThrow(() -> new IllegalStateException("締めイベントが見つかりません: id=" + closeEventId));
        allocationRepository.findByIdForUpdate(allocationId)
                .orElseThrow(() -> new IllegalStateException("割当が見つかりません: id=" + allocationId));
        ShiftBudgetMonthlyCloseEventEntity event = eventRepository.findByIdForUpdate(closeEventId)
                .orElseThrow(() -> new IllegalStateException("締めイベントが見つかりません: id=" + closeEventId));
        if (event.getState() != ShiftBudgetCloseEventState.PREPARED) {
            log.info("F08.7 期間締め Abort: PREPARED でないためスキップ: eventId={}, state={}", closeEventId, event.getState());
            return;
        }
        consumptionRepository.clearCloseEvent(closeEventId);
        event.markAborted();
        eventRepository.save(event);
        log.info("F08.7 期間締め Abort: eventId={}, reason={}", closeEventId, reason);
    }

    private Map<Long, LocalDate> slotDates(List<ShiftBudgetConsumptionEntity> rows) {
        List<Long> slotIds = rows.stream().map(ShiftBudgetConsumptionEntity::getSlotId).distinct().toList();
        Map<Long, LocalDate> dates = new HashMap<>();
        if (slotIds.isEmpty()) {
            return dates;
        }
        for (Object[] r : rateQueryRepository.findSlotDates(slotIds)) {
            dates.put(((Number) r[0]).longValue(), toLocalDate(r[1]));
        }
        return dates;
    }

    /** ネイティブのスカラー投影の DATE（{@code java.sql.Date} などで戻りうる）を LocalDate に直す。 */
    static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate d) {
            return d;
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }

    /** 勤務枠の移動で取消す（キャッシュを減算し、必要なら失敗イベントを残す）。 */
    private void cancelMoved(ShiftBudgetAllocationEntity allocation, ShiftBudgetConsumptionEntity row,
                             ShiftBudgetCancelReason reason, YearMonth periodKey, LocalDate movedTo, boolean recordFailure) {
        consumptionRepository.retireLiveCancelledBeforeCancel(row.getSlotId(), row.getUserId());
        row.cancel(reason);
        consumptionRepository.save(row);
        allocationRepository.decrementConsumedAmount(allocation.getId(), row.getAmount());
        log.warn("F08.7 期間締め Prepare: 勤務枠の日付変更により取消: allocId={}, slotId={}, userId={}, reason={}, movedTo={}",
                allocation.getId(), row.getSlotId(), row.getUserId(), reason, movedTo);
        if (recordFailure) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("operation", reason.name());
            payload.put("allocation_id", allocation.getId());
            payload.put("period_key", periodKey.toString());
            payload.put("slot_id", row.getSlotId());
            payload.put("user_id", row.getUserId());
            payload.put("moved_to", movedTo.toString());
            failedEventService.recordFailure(allocation.getOrganizationId(), ShiftBudgetFailedEventType.MONTHLY_CLOSE,
                    allocation.getId(), payload, reason.name() + ": slot=" + row.getSlotId() + ", user=" + row.getUserId());
        }
    }

    /**
     * Prepare の結果。
     *
     * @param closeEventId 締めイベント ID（UUIDv7）
     * @param lineCount    印を付けた PLANNED 件数
     * @param plannedTotal 印を付けた PLANNED の合計
     * @param journalDate  仕訳日（min(締め日, 割当終了日)）
     */
    public record PrepareResult(UUID closeEventId, int lineCount, BigDecimal plannedTotal, LocalDate journalDate) {
    }
}
