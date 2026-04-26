package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.SwapRequestStatus;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * シフト交代リクエストリポジトリ。
 */
public interface ShiftSwapRequestRepository extends JpaRepository<ShiftSwapRequestEntity, Long> {

    /**
     * シフト枠の交代リクエスト一覧を取得する。
     */
    List<ShiftSwapRequestEntity> findBySlotId(Long slotId);

    /**
     * リクエスターの交代リクエスト一覧をステータスでフィルタして取得する。
     */
    List<ShiftSwapRequestEntity> findByRequesterIdAndStatus(Long requesterId, SwapRequestStatus status);

    /**
     * リクエスターの全交代リクエスト一覧を取得する。
     */
    List<ShiftSwapRequestEntity> findByRequesterIdOrderByCreatedAtDesc(Long requesterId);

    /**
     * 承諾者の交代リクエスト一覧を取得する。
     */
    List<ShiftSwapRequestEntity> findByAccepterIdOrderByCreatedAtDesc(Long accepterId);

    /**
     * 特定ステータスの交代リクエスト一覧を取得する（管理者用）。
     */
    List<ShiftSwapRequestEntity> findByStatusOrderByCreatedAtAsc(SwapRequestStatus status);

    /**
     * オープンコール中の交代リクエスト一覧を取得する。
     */
    List<ShiftSwapRequestEntity> findByIsOpenCallTrueAndStatus(SwapRequestStatus status);
}
