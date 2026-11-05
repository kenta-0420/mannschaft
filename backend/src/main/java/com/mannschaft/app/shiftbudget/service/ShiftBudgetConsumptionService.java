package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.shiftbudget.ShiftBudgetCancelReason;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import com.mannschaft.app.shiftbudget.port.ShiftBudgetPublicationPort;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F08.7 シフト予算消化記録サービス（Phase 9-β、F03.5 hook 内ロジック）。
 *
 * <p>設計書 F08.7 (v1.2) §11.1 擬似コード忠実実装。</p>
 *
 * <p>シフト公開時 ({@code ShiftPublishedEvent} AFTER_COMMIT) に
 * {@link com.mannschaft.app.shiftbudget.listener.ShiftBudgetConsumptionRecordListener}
 * から呼び出される。</p>
 *
 * <p>記録の前に旧公開のPLANNEDを別TXで掃除し、単一記録は親を最初にロックする。</p>
 * <p>単一記録の処理順序:</p>
 * <ol>
 *   <li>現在の公開状態とイベント世代を検証し、同世代の既存PLANNEDはno-op</li>
 *   <li>(slot_id, user_id, CONFIRMED) が既存 → 例外 (CONFIRMED_RECORD_IMMUTABLE)</li>
 *   <li>新規 PLANNED INSERT</li>
 *   <li>{@code allocation.consumed_amount} アトミック加算</li>
 * </ol>
 *
 * <p>旧消化の取消claimと割当減算は同一TXで行い、片方の失敗は両方を巻き戻す。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftBudgetConsumptionService {

    private static final String DEFAULT_CURRENCY = "JPY";

    private final ShiftBudgetConsumptionRepository consumptionRepository;
    private final ShiftBudgetAllocationRepository allocationRepository;
    private final ShiftBudgetPublicationPort publicationPort;

    /** 既存LocalDateTime列と同じ業務壁時計を使う。 */
    @Qualifier("wallClock")
    private final Clock wallClock;

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
     * @param publicationGeneration 記録対象の公開世代
     * @return 実際に新規記録した場合だけtrue
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordSingleConsumption(Long allocationId,
                                           Long shiftScheduleId,
                                           Long slotId,
                                           Long userId,
                                           BigDecimal hourlyRate,
                                           BigDecimal hours,
                                           long publicationGeneration) {
        // 必ず最初のSQLで親をロックし、この消化のコミットまで公開変更を待たせる。
        var publication = publicationPort.lockPublication(shiftScheduleId);
        if (publication.isEmpty() || !publication.get().accepts(publicationGeneration)) {
            return false;
        }
        var planned = consumptionRepository.findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(
                slotId, userId, ShiftBudgetConsumptionStatus.PLANNED);
        if (planned.isPresent()) {
            if (planned.get().getPublicationGeneration() == publicationGeneration) {
                return false;
            }
            // 有効組織の旧世代は先に掃除する。OFF組織などの残存行は消さず、このslot/userを個別失敗にする。
            throw new IllegalStateException("Old planned consumption remains: shift=" + shiftScheduleId);
        }
        if (consumptionRepository.findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(
                slotId, userId, ShiftBudgetConsumptionStatus.CONFIRMED).isPresent()) {
            throw new IllegalStateException("CONFIRMED record already exists: slot=" + slotId + ", user=" + userId);
        }
        BigDecimal safeRate = hourlyRate != null ? hourlyRate : BigDecimal.ZERO;
        BigDecimal amount = safeRate.multiply(hours).setScale(2, java.math.RoundingMode.HALF_UP);
        consumptionRepository.save(ShiftBudgetConsumptionEntity.builder()
                .allocationId(allocationId).shiftId(shiftScheduleId).slotId(slotId).userId(userId)
                .publicationGeneration(publicationGeneration)
                .hourlyRateSnapshot(safeRate).hours(hours).amount(amount).currency(DEFAULT_CURRENCY)
                .status(ShiftBudgetConsumptionStatus.PLANNED)
                .recordedAt(LocalDateTime.now(wallClock)).build());
        requireAllocationUpdate(allocationRepository.incrementConsumedAmount(allocationId, amount), allocationId);
        return true;
    }

    /** 旧消化の計上先IDを一括取得する。フラグ判定用の参照だけで、取消や親ロックは行わない。 */
    @Transactional(readOnly = true)
    public List<Long> findOlderPlannedOrganizationIds(Long shiftScheduleId, long publicationGeneration) {
        if (publicationGeneration == 0) {
            return List.of();
        }
        return consumptionRepository.findPlannedCancellationRows(shiftScheduleId, publicationGeneration - 1).stream()
                .map(ShiftBudgetConsumptionRepository.PlannedCancellationRow::getOrganizationId).distinct().toList();
    }

    /** 新しい公開の記録前に、有効な組織の旧世代を取り外された枠・利用者も含めて掃除する。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PublicationCleanup cancelOldPlannedForPublication(Long shiftScheduleId, long publicationGeneration,
                                                            Set<Long> enabledOrganizationIds) {
        var publication = publicationPort.lockPublication(shiftScheduleId);
        if (publication.isEmpty() || !publication.get().accepts(publicationGeneration)) {
            return new PublicationCleanup(false, CancellationResult.empty());
        }
        CancellationResult cancellation = publicationGeneration == 0
                ? CancellationResult.empty()
                : cancelClaimed(consumptionRepository.findPlannedCancellationRows(
                                shiftScheduleId, publicationGeneration - 1).stream()
                        .filter(row -> enabledOrganizationIds.contains(row.getOrganizationId())).toList(),
                        ShiftBudgetCancelReason.RE_PUBLISHED);
        return new PublicationCleanup(true, cancellation);
    }

    /** 世代を持たない既存呼出元は、移行前の世代0だけを取り消す。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cancelAllForShift(Long shiftScheduleId) {
        return cancelClaimed(shiftScheduleId, 0, ShiftBudgetCancelReason.SHIFT_DELETED).cancelledCount();
    }

    /** イベント/候補選択時の世代上限を超える消化には触れない。親の不在でも取消可能。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CancellationResult cancelAllForShift(Long shiftScheduleId, long generationUpperBound) {
        return cancelClaimed(shiftScheduleId, generationUpperBound, ShiftBudgetCancelReason.SHIFT_DELETED);
    }

    private CancellationResult cancelClaimed(Long shiftScheduleId, long generationUpperBound,
                                             ShiftBudgetCancelReason reason) {
        return cancelClaimed(consumptionRepository.findPlannedCancellationRows(shiftScheduleId, generationUpperBound), reason);
    }

    private CancellationResult cancelClaimed(List<ShiftBudgetConsumptionRepository.PlannedCancellationRow> rows,
                                             ShiftBudgetCancelReason reason) {
        Map<Long, Long> countsByOrganization = new LinkedHashMap<>();
        Map<Long, Long> organizationsByAllocation = new LinkedHashMap<>();
        int count = 0;
        // 共通の割当ID→消化ID順。割当を先取りせず、claimできた行だけ同じTXで減算する。
        for (var row : rows) {
            int claimed = consumptionRepository.cancelPlannedById(
                    row.getConsumptionId(), reason, LocalDateTime.now(wallClock));
            if (claimed != 1) {
                continue;
            }
            requireAllocationUpdate(allocationRepository.decrementConsumedAmount(
                    row.getAllocationId(), row.getAmount()), row.getAllocationId());
            count++;
            countsByOrganization.merge(row.getOrganizationId(), 1L, Long::sum);
            organizationsByAllocation.put(row.getAllocationId(), row.getOrganizationId());
        }
        return new CancellationResult(count, Map.copyOf(countsByOrganization),
                Map.copyOf(organizationsByAllocation));
    }

    private void requireAllocationUpdate(int updated, Long allocationId) {
        if (updated != 1) {
            throw new IllegalStateException("Allocation update failed: allocation=" + allocationId);
        }
    }

    /** 有効な公開の取消0件と、古いイベントによる不受理を区別する。 */
    public record PublicationCleanup(boolean accepted, CancellationResult cancellation) {
    }

    /** 監査と閾値再評価には、実際にclaimした行の組織・割当だけを渡す。 */
    public record CancellationResult(int cancelledCount, Map<Long, Long> countsByOrganization,
                                     Map<Long, Long> organizationsByAllocation) {
        /** 変更なしを表す。 */
        public static CancellationResult empty() {
            return new CancellationResult(0, Map.of(), Map.of());
        }
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
        consumptionRepository
                .findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(
                        slotId, userId, ShiftBudgetConsumptionStatus.PLANNED)
                .ifPresent(c -> {
                    Long allocationId = c.getAllocationId();
                    BigDecimal amount = c.getAmount();
                    if (consumptionRepository.cancelPlannedById(
                            c.getId(), reason, LocalDateTime.now(wallClock)) == 1) {
                        requireAllocationUpdate(allocationRepository.decrementConsumedAmount(allocationId, amount),
                                allocationId);
                    }
                });
    }
}
