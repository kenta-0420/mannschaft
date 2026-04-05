package com.mannschaft.app.moderation.repository;

import com.mannschaft.app.moderation.ViolationType;
import com.mannschaft.app.moderation.entity.UserViolationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * ユーザー違反リポジトリ。
 */
public interface UserViolationRepository extends JpaRepository<UserViolationEntity, Long> {

    /**
     * ユーザーの有効な違反一覧を取得する。
     */
    List<UserViolationEntity> findByUserIdAndIsActiveTrueOrderByCreatedAtDesc(Long userId);

    /**
     * ユーザーの全違反一覧を取得する。
     */
    List<UserViolationEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * ユーザーの有効な特定種別の違反数を取得する。
     */
    long countByUserIdAndViolationTypeAndIsActiveTrue(Long userId, ViolationType violationType);

    /**
     * ユーザーの有効な違反総数を取得する。
     */
    long countByUserIdAndIsActiveTrue(Long userId);

    /**
     * 有効な違反総数を取得する。
     */
    long countByIsActiveTrue();

    /**
     * アクションIDで違反を検索する。
     */
    UserViolationEntity findByActionId(Long actionId);

    /**
     * ユーザーの有効な違反を一括無効化する。
     */
    @Modifying
    @Query("UPDATE UserViolationEntity v SET v.isActive = false WHERE v.userId = :userId AND v.isActive = true")
    int deactivateAllByUserId(@Param("userId") Long userId);

    /**
     * ヤバいやつ認定ユーザー数を取得する（有効違反が閾値以上のユーザー数）。
     */
    @Query(value = "SELECT COUNT(DISTINCT v.user_id) FROM user_violations v " +
            "WHERE v.is_active = true " +
            "GROUP BY v.user_id HAVING COUNT(*) >= :threshold",
            nativeQuery = true)
    List<Long> findYabaiUserIds(@Param("threshold") int threshold);
}
