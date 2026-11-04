package com.mannschaft.app.parking.repository;

import com.mannschaft.app.parking.entity.ParkingWatchlistEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * ウォッチリストリポジトリ。
 */
public interface ParkingWatchlistRepository extends JpaRepository<ParkingWatchlistEntity, Long> {

    List<ParkingWatchlistEntity> findByUserIdAndScopeTypeAndScopeIdAndIsActiveTrue(Long userId, String scopeType, Long scopeId);

    Optional<ParkingWatchlistEntity> findByIdAndUserIdAndScopeTypeAndScopeId(Long id, Long userId, String scopeType, Long scopeId);

    /**
     * 退会30日後の強匿名化専用。所有列 {@code user_id} で本人の行を一括物理削除する（行ごとのループを行わない）。
     *
     * @param userId 完全削除された利用者 ID
     * @return 削除件数
     */
    @Modifying
    @Query(value = "DELETE FROM parking_watchlist WHERE user_id = :userId", nativeQuery = true)
    int deleteAllOwnedByUserId(@Param("userId") Long userId);
}
