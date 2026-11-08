package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageUserAdmissionLockEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/** 入村の USER 単位直列化ロック行（CMP-260827-1808）。 */
public interface VillageUserAdmissionLockRepository extends JpaRepository<VillageUserAdmissionLockEntity, UUID> {

    /**
     * USER のロック行を作成または更新し、その行の排他ロックを呼出側の取引の終わりまで保持する。
     *
     * <p>{@code ON DUPLICATE KEY UPDATE} は重複時に共有ロックではなく排他ロックを取るため、
     * 同じ USER の入村が同時に来ても共有ロックの昇格によるデッドロックが起きない。
     * 新規作成時の id には呼出側で生成した UUIDv7 を渡す（重複時は使われない）。</p>
     *
     * @return 影響行数（新規作成 1・更新 2）
     */
    @Modifying
    @Query(value = "INSERT INTO village_user_admission_locks (id, user_id, locked_at) "
            + "VALUES (UUID_TO_BIN(:id), :userId, UTC_TIMESTAMP(6)) "
            + "ON DUPLICATE KEY UPDATE locked_at = UTC_TIMESTAMP(6)", nativeQuery = true)
    int lockForAdmission(@Param("id") String id, @Param("userId") Long userId);

    /**
     * 退会30日後の強匿名化で、本人のロック行を物理削除する（CMP-260822-1243 の判断一覧で「強消去」）。
     *
     * @return 削除件数
     */
    @Modifying
    @Query(value = "DELETE FROM village_user_admission_locks WHERE user_id = :userId", nativeQuery = true)
    int deleteAllOwnedByUserId(@Param("userId") Long userId);
}
