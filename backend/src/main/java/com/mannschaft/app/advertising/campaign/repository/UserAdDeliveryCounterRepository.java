package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.UserAdDeliveryCounter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * F09.17 フリークエンシーキャップ永続層リポジトリ。
 * Valkey ホットカウンタからの日次 flush と保持期間 90 日のクリーンアップを担う。
 */
public interface UserAdDeliveryCounterRepository
        extends JpaRepository<UserAdDeliveryCounter, UUID> {

    /** ユーザー × 週開始日でユニーク取得。 */
    Optional<UserAdDeliveryCounter> findByUserIdAndWeekStartDate(Long userId, LocalDate weekStartDate);

    /** 保持期間超過分の物理削除 (日次バッチ)。 */
    @Modifying
    @Query("DELETE FROM UserAdDeliveryCounter c WHERE c.weekStartDate < :threshold")
    int deleteOlderThan(@Param("threshold") LocalDate threshold);
}
