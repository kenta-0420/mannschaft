package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.shiftbudget.ShiftBudgetCancelReason;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

/**
 * F08.7 シフト予算消化記録サービス（Phase 9-β、F03.5 hook 内ロジック）。
 *
 * <p>設計書 F08.7 (v1.2) §11.1 擬似コード忠実実装。</p>
 *
 * <p>シフト公開時 ({@code ShiftPublishedEvent} AFTER_COMMIT) に
 * {@link com.mannschaft.app.shiftbudget.listener.ShiftBudgetConsumptionRecordListener}
 * から呼び出される。</p>
 *
 * <p>処理シリアライズ:</p>
 * <ol>
 *   <li>同一 (slot_id, user_id, PLANNED) 既存レコード → CANCELLED 遷移
 *       + {@code allocation.consumed_amount} 減算</li>
 *   <li>(slot_id, user_id, CONFIRMED) が既存 → 例外 (CONFIRMED_RECORD_IMMUTABLE)</li>
 *   <li>新規 PLANNED INSERT</li>
 *   <li>{@code allocation.consumed_amount} アトミック加算</li>
 * </ol>
 *
 * <p><strong>重要</strong>: (4) で二重加算を防ぐため、(1) で減算 → (3) で INSERT → (4) で加算
 * の順序を厳守する（設計書 §11.1 設計上のポイント）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftBudgetConsumptionService {

    private static final String DEFAULT_CURRENCY = "JPY";

    private final ShiftBudgetConsumptionRepository consumptionRepository;
    private final ShiftBudgetAllocationRepository allocationRepository;

    /**
     * 単一 (slot, user) 分の消化を記録する。
     *
     * <p>呼び出し側 (Listener) は、{@code allocation_id} を
     * {@link ShiftBudgetAllocationRepository#findContainingPeriod} で解決済みで渡す。
     * allocation 不在時の WARN+no-op 制御は Listener 側で実施 (Q3 御裁可)。</p>
     *
     * <p>例外: CONFIRMED が既存の場合は {@link IllegalStateException} を投げる
     * （Listener 側で個別 catch して該当 (slot,user) のみスキップ）。</p>
     *
     * @param allocationId       割当ID（呼出側で解決済み）
     * @param shiftScheduleId    シフトスケジュールID
     * @param slotId             スロットID
     * @param userId             ユーザーID
     * @param hourlyRate         適用時給（NULL/0 はそのまま受け入れ。設計書 §11 「時給未設定」参照）
     * @param hours              勤務時間
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSingleConsumption(Long allocationId,
                                        Long shiftScheduleId,
                                        Long slotId,
                                        Long userId,
                                        BigDecimal hourlyRate,
                                        BigDecimal hours) {
        BigDecimal safeRate = hourlyRate != null ? hourlyRate : BigDecimal.ZERO;
        BigDecimal amount = safeRate.multiply(hours).setScale(2, java.math.RoundingMode.HALF_UP);

        // (1) 既存 PLANNED → CANCELLED 遷移 + consumed_amount 減算
        Optional<ShiftBudgetConsumptionEntity> existingPlanned = consumptionRepository
                .findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(
                        slotId, userId, ShiftBudgetConsumptionStatus.PLANNED);
        existingPlanned.ifPresent(existing -> {
            existing.cancel(ShiftBudgetCancelReason.RE_PUBLISHED);
            consumptionRepository.save(existing);
            // allocation の consumed_amount から差し引き
            allocationRepository.decrementConsumedAmount(
                    existing.getAllocationId(), existing.getAmount());
            log.debug("既存 PLANNED 消化を再公開でキャンセル: slot={}, user={}, amount={}",
                    slotId, userId, existing.getAmount());
        });

        // (2) CONFIRMED 既存 → 物理的に再 INSERT 不可（月次締め後の再公開拒否）
        Optional<ShiftBudgetConsumptionEntity> existingConfirmed = consumptionRepository
                .findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(
                        slotId, userId, ShiftBudgetConsumptionStatus.CONFIRMED);
        if (existingConfirmed.isPresent()) {
            throw new IllegalStateException(
                    "CONFIRMED record already exists. Cannot re-record consumption: slot=" + slotId
                            + ", user=" + userId);
        }

        // (3) 新規 PLANNED INSERT
        ShiftBudgetConsumptionEntity fresh = ShiftBudgetConsumptionEntity.builder()
                .allocationId(allocationId)
                .shiftId(shiftScheduleId)
                .slotId(slotId)
                .userId(userId)
                .hourlyRateSnapshot(safeRate)
                .hours(hours)
                .amount(amount)
                .currency(DEFAULT_CURRENCY)
                .status(ShiftBudgetConsumptionStatus.PLANNED)
                .recordedAt(LocalDateTime.now())
                .build();
        consumptionRepository.save(fresh);

        // (4) allocation.consumed_amount アトミック加算
        allocationRepository.incrementConsumedAmount(allocationId, amount);

        log.debug("消化を新規記録: allocation={}, slot={}, user={}, hours={}, amount={}",
                allocationId, slotId, userId, hours, amount);
    }

    /**
     * 指定シフトに紐付く全 PLANNED 消化を CANCELLED 遷移し、{@code consumed_amount} を減算する。
     *
     * <p>{@code ShiftArchivedEvent} 受信時の hook で呼び出される。</p>
     *
     * @param shiftScheduleId シフトスケジュールID
     * @return キャンセルした件数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cancelAllForShift(Long shiftScheduleId) {
        var consumptions = consumptionRepository.findByShiftIdAndDeletedAtIsNull(shiftScheduleId);
        int cancelledCount = 0;
        for (ShiftBudgetConsumptionEntity c : consumptions) {
            // CONFIRMED は変更不可（取消仕訳パターンが必要だが Phase 9-β スコープ外）
            if (c.getStatus() != ShiftBudgetConsumptionStatus.PLANNED) {
                continue;
            }
            c.cancel(ShiftBudgetCancelReason.SHIFT_DELETED);
            consumptionRepository.save(c);
            allocationRepository.decrementConsumedAmount(c.getAllocationId(), c.getAmount());
            cancelledCount++;
        }
        if (cancelledCount > 0) {
            log.info("シフトアーカイブに伴い PLANNED 消化を {} 件キャンセル: shift={}",
                    cancelledCount, shiftScheduleId);
        }
        return cancelledCount;
    }

    /**
     * 勤務時間を計算する（深夜跨ぎ対応: end_time が start_time より早ければ + 24h）。
     *
     * <p>設計書 §11 「月跨ぎシフト（深夜跨ぎ）」: 22:00-翌03:00 は 5 時間として
     * 開始日属する月に全額計上する。</p>
     *
     * @param startTime 開始時刻
     * @param endTime   終了時刻
     * @return 時間 (BigDecimal, 小数 2 桁)
     */
    public static BigDecimal calculateHours(LocalTime startTime, LocalTime endTime) {
        LocalDateTime start = LocalDateTime.of(LocalDate.now(), startTime);
        LocalDateTime end = LocalDateTime.of(LocalDate.now(), endTime);
        if (!end.isAfter(start)) {
            // 深夜跨ぎ: 翌日扱い
            end = end.plusDays(1);
        }
        long minutes = java.time.Duration.between(start, end).toMinutes();
        return BigDecimal.valueOf(minutes)
                .divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 指定 (slot, user) で CONFIRMED が存在するか（Listener 側で事前判定したい場合用）。
     */
    public boolean hasConfirmedRecord(Long slotId, Long userId) {
        return consumptionRepository
                .findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(
                        slotId, userId, ShiftBudgetConsumptionStatus.CONFIRMED)
                .isPresent();
    }

    /**
     * 単一 (slot, user) を CANCELLED 遷移する（必要時に外部から呼べる API）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelSingleConsumption(Long slotId, Long userId, ShiftBudgetCancelReason reason) {
        Optional<ShiftBudgetAllocationEntity> ignored;
        consumptionRepository
                .findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(
                        slotId, userId, ShiftBudgetConsumptionStatus.PLANNED)
                .ifPresent(c -> {
                    c.cancel(reason);
                    consumptionRepository.save(c);
                    allocationRepository.decrementConsumedAmount(c.getAllocationId(), c.getAmount());
                });
    }
}
