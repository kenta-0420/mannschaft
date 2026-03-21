package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.UserCalendarSyncSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * チーム・組織別カレンダー同期設定リポジトリ。
 */
public interface UserCalendarSyncSettingRepository extends JpaRepository<UserCalendarSyncSettingEntity, Long> {

    /**
     * ユーザーIDで同期設定一覧を取得する。
     */
    List<UserCalendarSyncSettingEntity> findByUserId(Long userId);

    /**
     * ユーザーID・スコープ種別・スコープIDで同期設定を取得する。
     */
    Optional<UserCalendarSyncSettingEntity> findByUserIdAndScopeTypeAndScopeId(Long userId, String scopeType, Long scopeId);

    /**
     * スコープ種別・スコープIDで有効な同期設定一覧を取得する。
     */
    List<UserCalendarSyncSettingEntity> findByScopeTypeAndScopeIdAndIsEnabledTrue(String scopeType, Long scopeId);

    /**
     * スコープ種別・スコープIDで同期設定を削除する。
     */
    void deleteByScopeTypeAndScopeId(String scopeType, Long scopeId);

    /**
     * 同期設定をUPSERTする。既存レコードがあれば更新、なければ挿入する。
     */
    @Modifying
    @Query(value = "INSERT INTO user_calendar_sync_settings " +
            "(user_id, scope_type, scope_id, is_enabled, created_at, updated_at) " +
            "VALUES (:userId, :scopeType, :scopeId, :isEnabled, NOW(), NOW()) " +
            "ON DUPLICATE KEY UPDATE is_enabled = :isEnabled, updated_at = NOW()",
            nativeQuery = true)
    void upsert(@Param("userId") Long userId,
                @Param("scopeType") String scopeType,
                @Param("scopeId") Long scopeId,
                @Param("isEnabled") boolean isEnabled);
}
