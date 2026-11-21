package com.mannschaft.app.shiftbudget.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.shiftbudget.ShiftBudgetCancelReason;
import com.mannschaft.app.shiftbudget.ShiftBudgetCloseEventState;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventType;
import com.mannschaft.app.shiftbudget.UuidBytes;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetMonthlyCloseEventEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetMonthlyCloseEventRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRecordDeferralRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * F08.7 シフト予算消化記録サービス（Phase 9-β、F03.5 hook 内ロジック。CMP-260909-2152 で期間締めに合わせて改修）。
 *
 * <p>設計書 F08.7 (v1.2) §11.1 擬似コード＋ CMP-260909-2152 確定設計 r12 §4。</p>
 *
 * <h2>記録（{@link #recordSingleConsumption}）</h2>
 * <ol>
 *   <li>発見（通常 SELECT。ロック集合の決定にのみ使う）</li>
 *   <li>L ＝ {計上先 T} ∪ {発見割当} を ID 昇順で FOR UPDATE</li>
 *   <li>枠×人の生存行を FOR UPDATE で再読し、割当が L 外なら {@link ShiftBudgetLockSetChangedException}（TX 全体ロールバック）</li>
 *   <li>スケジュールを FOR SHARE で current read（非公開なら記録しない）</li>
 *   <li>判定表: 印付き行が PREPARED／計上先期間が PREPARED → 遅延（DEFERRED）、計上先期間が CLOSED → SKIPPED_CLOSED、
 *       CONFIRMED 済み → SKIPPED_COUNTED、それ以外は通常記録</li>
 * </ol>
 *
 * <h2>取消（{@link #cancelForShift}）</h2>
 * <p>ロック順は 割当 → 消化 → スケジュール行（FOR SHARE）→ 締めイベント。非公開なら PLANNED を取消、公開中（PUBLISHED）なら
 * 現在の担当に無い PLANNED だけを整合取消する。印付き PLANNED（PREPARED 中）は遅延、Commit 後の CONFIRMED は取消さず失敗イベント
 * CANCEL_AFTER_CLOSE。</p>
 */
@Slf4j
@Service
public class ShiftBudgetConsumptionService {

    private static final String DEFAULT_CURRENCY = "JPY";

    /** ロック集合の不一致でやり直す最大試行回数（呼び出し側が数える）。 */
    public static final int MAX_LOCK_ATTEMPTS = 3;

    private final ShiftBudgetConsumptionRepository consumptionRepository;
    private final ShiftBudgetAllocationRepository allocationRepository;
    private final ShiftBudgetMonthlyCloseEventRepository eventRepository;
    private final ShiftBudgetRecordDeferralRepository deferralRepository;
    private final ShiftBudgetRateQueryRepository rateQueryRepository;
    private final ShiftBudgetFailedEventService failedEventService;
    private final ShiftBudgetLockStepHooks hooks;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNewTx;

    public ShiftBudgetConsumptionService(ShiftBudgetConsumptionRepository consumptionRepository,
                                         ShiftBudgetAllocationRepository allocationRepository,
                                         ShiftBudgetMonthlyCloseEventRepository eventRepository,
                                         ShiftBudgetRecordDeferralRepository deferralRepository,
                                         ShiftBudgetRateQueryRepository rateQueryRepository,
                                         ShiftBudgetFailedEventService failedEventService,
                                         ShiftBudgetLockStepHooks hooks,
                                         ObjectMapper objectMapper,
                                         PlatformTransactionManager transactionManager) {
        this.consumptionRepository = consumptionRepository;
        this.allocationRepository = allocationRepository;
        this.eventRepository = eventRepository;
        this.deferralRepository = deferralRepository;
        this.rateQueryRepository = rateQueryRepository;
        this.failedEventService = failedEventService;
        this.hooks = hooks;
        this.objectMapper = objectMapper;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 記録の結果（例外は真の失敗のみ）。 */
    public enum RecordResult {
        /** 記録した（既に同内容の PLANNED があるときも含む）。 */
        RECORDED,
        /** 締めの PREPARED を理由に遅延行へ回した。 */
        DEFERRED,
        /** 計上先期間が締め済みのため記録しない（未計上候補として見える）。 */
        SKIPPED_CLOSED,
        /** 枠×人が既に計上済み（CONFIRMED）。 */
        SKIPPED_COUNTED,
        /** スケジュールが公開中でない（PUBLISHED かつ未論理削除でない）。 */
        SKIPPED_NOT_PUBLISHED
    }

    /** 取消の結果。 */
    public record CancelOutcome(int cancelled, int deferred, int cancelAfterClose) {
    }

    // ───────────────────────── 記録 ─────────────────────────

    /**
     * 単一 (slot, user) 分の消化を記録する。
     *
     * @param allocationId    計上先の割当 ID（呼出側が勤務日から解決済み）
     * @param shiftScheduleId シフトスケジュール ID
     * @param slotId          スロット ID
     * @param userId          ユーザー ID
     * @param hourlyRate      適用時給（正数のみ。null・0・負数は副作用の前に {@link IllegalArgumentException} で拒否する）
     * @param hours           勤務時間
     * @param workDate        勤務日（勤務枠の slot_date。深夜跨ぎは開始日）
     * @return 記録の結果
     * @throws ShiftBudgetLockSetChangedException 発見後にロック対象の割当の集合が変わった（呼び出し側が計上先の解決からやり直す）
     */
    public RecordResult recordSingleConsumption(Long allocationId, Long shiftScheduleId, Long slotId, Long userId,
                                                BigDecimal hourlyRate, BigDecimal hours, LocalDate workDate) {
        // 0円・負数への置換は予算管理を黙って無効化する。既存PLANNEDの取消より前に拒否する。
        if (hourlyRate == null || hourlyRate.signum() <= 0) {
            throw new IllegalArgumentException("hourlyRate must be positive: slot=" + slotId + ", user=" + userId);
        }
        BigDecimal amount = hourlyRate.multiply(hours).setScale(2, java.math.RoundingMode.HALF_UP);

        // (1) 発見（通常 SELECT。ロック集合の決定にのみ使う）
        List<Long> discovered = consumptionRepository.findLiveAllocationIdsBySlotAndUser(slotId, userId);
        hooks.afterRecordDiscovered(shiftScheduleId, slotId, userId);

        return requiresNewTx.execute(status -> {
            // (2) L ＝ {T} ∪ {発見割当} を ID 昇順で FOR UPDATE
            Set<Long> lockSet = new TreeSet<>(discovered);
            lockSet.add(allocationId);
            ShiftBudgetAllocationEntity target = null;
            for (Long id : lockSet) {
                ShiftBudgetAllocationEntity locked = allocationRepository.findByIdForUpdate(id).orElse(null);
                if (id.equals(allocationId)) {
                    target = locked;
                }
            }
            if (target == null || target.getDeletedAt() != null) {
                throw new ShiftBudgetLockSetChangedException("計上先の割当が消滅・論理削除されました: allocId=" + allocationId);
            }

            // (3) 枠×人の生存行を FOR UPDATE で再読し、割当が L 外ならロック集合の不一致
            List<ShiftBudgetConsumptionEntity> rows = consumptionRepository.lockLiveBySlotAndUser(slotId, userId);
            for (ShiftBudgetConsumptionEntity row : rows) {
                if (!lockSet.contains(row.getAllocationId())) {
                    throw new ShiftBudgetLockSetChangedException("発見後に割当の集合が変わりました: slot=" + slotId
                            + ", user=" + userId + ", allocId=" + row.getAllocationId());
                }
            }

            // (4) 現在の勤務枠日付が呼出時の勤務日と一致するか（外なら計上先の解決からやり直す）
            List<Object[]> slotRows = rateQueryRepository.findSlotDates(List.of(slotId));
            if (!slotRows.isEmpty()
                    && !ShiftBudgetCloseStageService.toLocalDate(slotRows.get(0)[1]).equals(workDate)) {
                throw new ShiftBudgetLockSetChangedException("勤務枠の日付が変わりました: slot=" + slotId);
            }

            // (5) スケジュール（公開状態の正本）を FOR SHARE で current read
            if (!isPublished(shiftScheduleId)) {
                return RecordResult.SKIPPED_NOT_PUBLISHED;
            }

            ShiftBudgetConsumptionEntity existingPlanned = null;
            for (ShiftBudgetConsumptionEntity row : rows) {
                if (row.getStatus() == ShiftBudgetConsumptionStatus.CONFIRMED) {
                    return RecordResult.SKIPPED_COUNTED;
                }
                existingPlanned = row;
            }

            // 印付き行: そのイベントが PREPARED なら取消も作成もせず遅延
            if (existingPlanned != null && existingPlanned.getCloseEventId() != null) {
                ShiftBudgetMonthlyCloseEventEntity marked = eventRepository
                        .findByIdForUpdate(existingPlanned.getCloseEventId()).orElse(null);
                if (marked != null && marked.getState() == ShiftBudgetCloseEventState.PREPARED) {
                    defer(shiftScheduleId, marked.getId());
                    return RecordResult.DEFERRED;
                }
            }

            // 計上先期間の締めイベント（work_date を含む期間。月キーでなく固定済み期間の範囲で引く）
            List<ShiftBudgetMonthlyCloseEventEntity> covering = eventRepository.findCoveringDateShared(allocationId, workDate,
                    List.of(ShiftBudgetCloseEventState.PREPARED, ShiftBudgetCloseEventState.CLOSED));
            for (ShiftBudgetMonthlyCloseEventEntity e : covering) {
                if (e.getState() == ShiftBudgetCloseEventState.PREPARED) {
                    defer(shiftScheduleId, e.getId());
                    return RecordResult.DEFERRED;
                }
            }
            if (!covering.isEmpty()) {
                return RecordResult.SKIPPED_CLOSED;
            }

            // 同内容の PLANNED が既にあれば何もしない（取消→再作成しても結果は同じで、取消履歴を無意味に増やすだけ）。
            // 同内容は 割当・勤務日・金額に加え、時給スナップショットと勤務時間も一致すること（CMP-260909-2152 α 検分 P2-1）。
            // 金額だけで判定すると 1,000円×4h → 2,000円×2h のような同額の再公開で古い時給・勤務時間が残り、
            // 勤務時間の集計（ShiftBudgetSummaryService）と記録時給の表示（#4527）が誤る。
            if (existingPlanned != null && isSameContent(existingPlanned, allocationId, workDate, hourlyRate, hours, amount)) {
                return RecordResult.RECORDED;
            }

            // (1') 既存 PLANNED → CANCELLED 遷移 + consumed_amount 減算
            if (existingPlanned != null) {
                consumptionRepository.retireLiveCancelledBeforeCancel(slotId, userId);
                existingPlanned.cancel(ShiftBudgetCancelReason.RE_PUBLISHED);
                consumptionRepository.saveAndFlush(existingPlanned);
                allocationRepository.decrementConsumedAmount(existingPlanned.getAllocationId(), existingPlanned.getAmount());
            }

            // (3') 新規 PLANNED INSERT ＋ (4') consumed_amount 加算
            consumptionRepository.save(ShiftBudgetConsumptionEntity.builder()
                    .allocationId(allocationId)
                    .shiftId(shiftScheduleId)
                    .slotId(slotId)
                    .workDate(workDate)
                    .userId(userId)
                    .hourlyRateSnapshot(hourlyRate)
                    .hours(hours)
                    .amount(amount)
                    .currency(DEFAULT_CURRENCY)
                    .status(ShiftBudgetConsumptionStatus.PLANNED)
                    .recordedAt(LocalDateTime.now())
                    .build());
            allocationRepository.incrementConsumedAmount(allocationId, amount);
            log.debug("消化を新規記録: allocation={}, slot={}, user={}, hours={}, amount={}",
                    allocationId, slotId, userId, hours, amount);
            return RecordResult.RECORDED;
        });
    }

    /** 既存 PLANNED が、これから記録する内容（割当・勤務日・時給・勤務時間・金額）と同じか。数値は scale を無視して比べる。 */
    private static boolean isSameContent(ShiftBudgetConsumptionEntity existing, Long allocationId, LocalDate workDate,
                                         BigDecimal hourlyRate, BigDecimal hours, BigDecimal amount) {
        return existing.getAllocationId().equals(allocationId)
                && workDate.equals(existing.getWorkDate())
                && sameNumber(hourlyRate, existing.getHourlyRateSnapshot())
                && sameNumber(hours, existing.getHours())
                && sameNumber(amount, existing.getAmount());
    }

    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }

    /** 公開中（PUBLISHED かつ未論理削除）か。スケジュール行を FOR SHARE で読む（TX コミットまで保持）。 */
    private boolean isPublished(Long scheduleId) {
        List<Object[]> rows = rateQueryRepository.lockScheduleStatusForShare(scheduleId);
        if (rows.isEmpty()) {
            return false;
        }
        return "PUBLISHED".equals(String.valueOf(rows.get(0)[0])) && !truthy(rows.get(0)[1]);
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value instanceof Number n && n.longValue() != 0;
    }

    /** シフト×締めイベントの遅延行を作る（既にあれば何もしない）。 */
    private void defer(Long shiftId, UUID closeEventId) {
        deferralRepository.insertPendingIgnore(UuidBytes.toBytes(UuidV7.generate()), shiftId, UuidBytes.toBytes(closeEventId));
        log.info("F08.7 遅延記録: 締めの PREPARED を理由に記録・取消を遅延: shiftId={}, eventId={}", shiftId, closeEventId);
    }

    // ───────────────────────── 取消 ─────────────────────────

    /**
     * 指定シフトに紐付く PLANNED 消化を取消し、{@code consumed_amount} を減算する（件数のみ返す互換入口）。
     *
     * @return キャンセルした件数
     */
    public int cancelAllForShift(Long shiftScheduleId) {
        return cancelForShift(shiftScheduleId).cancelled();
    }

    /**
     * 取消（非公開なら PLANNED を取消、公開中なら現在の担当に無い PLANNED を整合取消）。通常の公開取消リスナー経路・遅延回収経路・
     * 整合バッチの共通の入口。ロック集合の不一致は最大 {@value #MAX_LOCK_ATTEMPTS} 回やり直し、尽きたら例外。
     */
    public CancelOutcome cancelForShift(Long shiftScheduleId) {
        ShiftBudgetLockSetChangedException last = null;
        for (int attempt = 1; attempt <= MAX_LOCK_ATTEMPTS; attempt++) {
            List<FailureNote> notes = new ArrayList<>();
            try {
                CancelOutcome outcome = cancelOnce(shiftScheduleId, notes);
                recordNotes(notes);
                return outcome;
            } catch (ShiftBudgetLockSetChangedException e) {
                last = e;
            }
        }
        throw last;
    }

    private CancelOutcome cancelOnce(Long shiftId, List<FailureNote> notes) {
        List<Long> discovered = consumptionRepository.findLiveAllocationIdsByShift(shiftId);
        if (discovered.isEmpty()) {
            return new CancelOutcome(0, 0, 0);
        }
        return requiresNewTx.execute(status -> {
            Set<Long> lockSet = new TreeSet<>(discovered);
            Map<Long, ShiftBudgetAllocationEntity> locked = new java.util.HashMap<>();
            for (Long id : lockSet) {
                allocationRepository.findByIdForUpdate(id).ifPresent(a -> locked.put(a.getId(), a));
            }
            List<ShiftBudgetConsumptionEntity> rows = consumptionRepository.lockLiveByShift(shiftId);
            for (ShiftBudgetConsumptionEntity row : rows) {
                if (!lockSet.contains(row.getAllocationId())) {
                    throw new ShiftBudgetLockSetChangedException("発見後に割当の集合が変わりました: shift=" + shiftId
                            + ", allocId=" + row.getAllocationId());
                }
            }

            List<Object[]> schedule = rateQueryRepository.lockScheduleStatusForShare(shiftId);
            String statusText = schedule.isEmpty() ? "DELETED"
                    : (truthy(schedule.get(0)[1]) ? "DELETED" : String.valueOf(schedule.get(0)[0]));
            hooks.afterCancelScheduleRead(shiftId, statusText);
            boolean published = "PUBLISHED".equals(statusText);
            Set<String> currentPairs = published ? currentPairs(shiftId) : null;

            int cancelled = 0;
            int deferred = 0;
            int afterClose = 0;
            for (ShiftBudgetConsumptionEntity row : rows) {
                boolean stillAssigned = published && currentPairs.contains(row.getSlotId() + ":" + row.getUserId());
                if (stillAssigned) {
                    continue;
                }
                if (row.getStatus() == ShiftBudgetConsumptionStatus.CONFIRMED) {
                    ShiftBudgetAllocationEntity allocation = locked.get(row.getAllocationId());
                    notes.add(new FailureNote(allocation.getOrganizationId(), allocation.getId(), "CANCEL_AFTER_CLOSE",
                            row.getSlotId(), row.getUserId(), shiftId));
                    afterClose++;
                    continue;
                }
                if (row.getCloseEventId() != null) {
                    ShiftBudgetMonthlyCloseEventEntity marked = eventRepository.findByIdForUpdate(row.getCloseEventId()).orElse(null);
                    if (marked != null && marked.getState() == ShiftBudgetCloseEventState.PREPARED) {
                        defer(shiftId, marked.getId());
                        deferred++;
                        continue;
                    }
                }
                consumptionRepository.retireLiveCancelledBeforeCancel(row.getSlotId(), row.getUserId());
                row.cancel(published ? ShiftBudgetCancelReason.USER_UNASSIGNED : ShiftBudgetCancelReason.SHIFT_DELETED);
                consumptionRepository.saveAndFlush(row);
                allocationRepository.decrementConsumedAmount(row.getAllocationId(), row.getAmount());
                cancelled++;
            }
            if (cancelled > 0) {
                log.info("シフトの公開失効・担当変更に伴い PLANNED 消化を {} 件キャンセル: shift={}", cancelled, shiftId);
            }
            return new CancelOutcome(cancelled, deferred, afterClose);
        });
    }

    /** シフトの現在の有効な (slot, user) 集合（"slotId:userId"）。 */
    private Set<String> currentPairs(Long shiftId) {
        Set<String> pairs = new HashSet<>();
        for (Object[] r : rateQueryRepository.findSlotAssignments(shiftId)) {
            Long slotId = ((Number) r[0]).longValue();
            String json = r[1] == null ? null : String.valueOf(r[1]);
            if (json == null || json.isBlank()) {
                continue;
            }
            try {
                List<Long> users = objectMapper.readValue(json, new TypeReference<List<Long>>() {
                });
                for (Long u : users) {
                    pairs.add(slotId + ":" + u);
                }
            } catch (Exception e) {
                log.warn("F08.7 取消: assigned_user_ids デシリアライズ失敗: slot={}, json={}", slotId, json, e);
            }
        }
        return pairs;
    }

    private record FailureNote(Long organizationId, Long allocationId, String operation, Long slotId, Long userId, Long shiftId) {
    }

    /** TX の外で失敗イベントを残す（取消 TX のロールバックで消えず、リトライで重複しない）。 */
    private void recordNotes(List<FailureNote> notes) {
        for (FailureNote n : notes) {
            try {
                Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("operation", n.operation());
                payload.put("allocation_id", n.allocationId());
                payload.put("shift_schedule_id", n.shiftId());
                payload.put("slot_id", n.slotId());
                payload.put("user_id", n.userId());
                failedEventService.recordFailure(n.organizationId(), ShiftBudgetFailedEventType.MONTHLY_CLOSE, n.allocationId(),
                        payload, n.operation() + ": 締め後の取消は訂正の CANCEL で対応してください（slot=" + n.slotId()
                                + ", user=" + n.userId() + "）");
            } catch (Exception e) {
                log.error("F08.7 取消: 失敗イベントの記録に失敗: {}", n, e);
            }
        }
    }

    // ───────────────────────── 補助 ─────────────────────────

    /**
     * 勤務時間を計算する（深夜跨ぎ対応: end_time が start_time より早ければ + 24h）。
     *
     * <p>設計書 §11 「月跨ぎシフト（深夜跨ぎ）」: 22:00-翌03:00 は 5 時間として開始日属する月に全額計上する。</p>
     */
    public static BigDecimal calculateHours(LocalTime startTime, LocalTime endTime) {
        LocalDateTime start = LocalDateTime.of(LocalDate.now(), startTime);
        LocalDateTime end = LocalDateTime.of(LocalDate.now(), endTime);
        if (!end.isAfter(start)) {
            end = end.plusDays(1);
        }
        long minutes = java.time.Duration.between(start, end).toMinutes();
        return BigDecimal.valueOf(minutes)
                .divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP);
    }

    /** 指定 (slot, user) で CONFIRMED が存在するか（Listener 側で事前判定したい場合用）。 */
    public boolean hasConfirmedRecord(Long slotId, Long userId) {
        return consumptionRepository
                .findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(slotId, userId, ShiftBudgetConsumptionStatus.CONFIRMED)
                .isPresent();
    }

    /** 単一 (slot, user) を CANCELLED 遷移する（必要時に外部から呼べる API）。 */
    public void cancelSingleConsumption(Long slotId, Long userId, ShiftBudgetCancelReason reason) {
        requiresNewTx.executeWithoutResult(status -> consumptionRepository
                .findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(slotId, userId, ShiftBudgetConsumptionStatus.PLANNED)
                .ifPresent(c -> {
                    consumptionRepository.retireLiveCancelledBeforeCancel(slotId, userId);
                    c.cancel(reason);
                    consumptionRepository.save(c);
                    allocationRepository.decrementConsumedAmount(c.getAllocationId(), c.getAmount());
                }));
    }
}
