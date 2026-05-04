package com.mannschaft.app.shiftbudget.repository;

import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * F08.7 シフト予算消化記録リポジトリ。
 *
 * <p>設計書 F08.7 (v1.2) §5.3 / §11 / §11.1 に準拠。</p>
 */
@Repository
public interface ShiftBudgetConsumptionRepository
        extends JpaRepository<ShiftBudgetConsumptionEntity, Long> {

    /**
     * 同一 (slot_id, user_id, status) の生存レコードを検索する。
     *
     * <p>設計書 §11.1 の (1) 同一 (slot, user) 再 INSERT パターンで利用。
     * 既存 PLANNED を CANCELLED に遷移させる前段の検索に相当。</p>
     */
    Optional<ShiftBudgetConsumptionEntity> findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(
            Long slotId, Long userId, ShiftBudgetConsumptionStatus status);

    /**
     * 指定 allocation 配下で指定ステータス群に該当する生存レコードを取得する。
     */
    List<ShiftBudgetConsumptionEntity> findByAllocationIdAndStatusInAndDeletedAtIsNull(
            Long allocationId, Collection<ShiftBudgetConsumptionStatus> statuses);

    /**
     * 指定 allocation 配下で指定ステータス群に該当する生存レコードが存在するかを判定する。
     *
     * <p>設計書 §5.2 HAS_CONSUMPTIONS 制約チェックに対応。
     * {@link ShiftBudgetConsumptionStatus#PLANNED}/{@link ShiftBudgetConsumptionStatus#CONFIRMED}
     * が残っていれば allocation の論理削除を 409 で拒否する判定に使う。</p>
     */
    boolean existsByAllocationIdAndStatusInAndDeletedAtIsNull(
            Long allocationId, Collection<ShiftBudgetConsumptionStatus> statuses);

    /**
     * 指定シフトに紐付く生存消化レコードを全件取得する（シフトキャンセル hook 用）。
     */
    List<ShiftBudgetConsumptionEntity> findByShiftIdAndDeletedAtIsNull(Long shiftId);
}
