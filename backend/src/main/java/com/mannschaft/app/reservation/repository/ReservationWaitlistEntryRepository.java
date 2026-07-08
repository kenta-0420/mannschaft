package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.WaitlistStatus;
import com.mannschaft.app.reservation.entity.ReservationWaitlistEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * キャンセル待ち（waitlist）エントリのリポジトリ（F03.4.5 §6.1）。
 */
public interface ReservationWaitlistEntryRepository
        extends JpaRepository<ReservationWaitlistEntryEntity, UUID> {

    /**
     * 同一 (slot, user, status) の存在チェック（重複登録ガード・WAITING）。
     */
    boolean existsBySlotIdAndUserIdAndStatus(Long slotId, Long userId, WaitlistStatus status);

    /**
     * ユーザーの指定状態のエントリ数（1 ユーザー同時 WAITING 上限判定）。
     */
    long countByUserIdAndStatus(Long userId, WaitlistStatus status);

    /**
     * 枠の指定状態のエントリ数（1 枠あたり WAITING 上限判定・ADMIN 件数表示）。
     */
    long countBySlotIdAndStatus(Long slotId, WaitlistStatus status);

    /**
     * (slot, user, status) でエントリを解決する（本人取消・消し込みの IDOR 安全な解決）。
     *
     * <p>userId 絞り込みにより他人のエントリは構造的に掴めない（§7 IDOR: entry は AndUserId 解決）。</p>
     */
    Optional<ReservationWaitlistEntryEntity> findBySlotIdAndUserIdAndStatus(
            Long slotId, Long userId, WaitlistStatus status);

    /**
     * 枠の指定状態のエントリ一覧（空き通知の宛先列挙）。
     */
    List<ReservationWaitlistEntryEntity> findBySlotIdAndStatus(Long slotId, WaitlistStatus status);

    /**
     * ユーザーの指定状態のエントリ一覧（本人の待ち一覧・新しい順）。
     */
    List<ReservationWaitlistEntryEntity> findByUserIdAndStatusOrderByCreatedAtDesc(
            Long userId, WaitlistStatus status);

    /**
     * 枠開始時刻を過ぎた WAITING エントリを列挙する（失効クリーンアップ・§6.1 バッチ）。
     *
     * <p>slot は同一 reservation ドメインの JPA エンティティのため、id 突合の EXISTS サブクエリで
     * 「枠開始 &lt;= 現在」を判定する（{@code slotDate &lt; today OR (slotDate = today AND startTime &lt;= nowTime)}）。</p>
     */
    @Query("SELECT w FROM ReservationWaitlistEntryEntity w "
            + "WHERE w.status = :status AND EXISTS ("
            + "  SELECT s FROM ReservationSlotEntity s WHERE s.id = w.slotId "
            + "  AND (s.slotDate < :today OR (s.slotDate = :today AND s.startTime <= :nowTime)))")
    List<ReservationWaitlistEntryEntity> findExpiredWaiting(
            @Param("status") WaitlistStatus status,
            @Param("today") LocalDate today,
            @Param("nowTime") LocalTime nowTime);
}
