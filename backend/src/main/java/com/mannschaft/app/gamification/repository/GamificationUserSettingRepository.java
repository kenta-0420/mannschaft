package com.mannschaft.app.gamification.repository;

import com.mannschaft.app.gamification.entity.GamificationUserSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * ゲーミフィケーションユーザー設定リポジトリ。
 */
public interface GamificationUserSettingRepository extends JpaRepository<GamificationUserSettingEntity, Long> {

    /**
     * ユーザーIDとスコープで設定を検索する。
     */
    Optional<GamificationUserSettingEntity> findByUserIdAndScopeTypeAndScopeId(
            Long userId, String scopeType, Long scopeId);

    /** 強匿名化専用。対象利用者の行を論理削除済みも含めて物理削除する。 */
    @Modifying
    @Query(value = "DELETE FROM gamification_user_settings WHERE user_id = :userId", nativeQuery = true)
    int deleteByUserId(@Param("userId") Long userId);
}
