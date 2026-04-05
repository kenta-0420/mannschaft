package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.UserGoogleCalendarConnectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Google Calendar OAuth連携情報リポジトリ。
 */
public interface UserGoogleCalendarConnectionRepository extends JpaRepository<UserGoogleCalendarConnectionEntity, Long> {

    /**
     * ユーザーIDで連携情報を取得する。
     */
    Optional<UserGoogleCalendarConnectionEntity> findByUserId(Long userId);

    /**
     * ユーザーIDでアクティブな連携情報を取得する。
     */
    Optional<UserGoogleCalendarConnectionEntity> findByUserIdAndIsActiveTrue(Long userId);

    /**
     * ユーザーIDでアクティブな連携が存在するかを判定する。
     */
    boolean existsByUserIdAndIsActiveTrue(Long userId);

    /**
     * 接続情報をUPSERTする。既存レコードがあれば更新、なければ挿入する。
     */
    @Modifying
    @Query(value = "INSERT INTO user_google_calendar_connections " +
            "(user_id, google_account_email, google_calendar_id, refresh_token, is_active, " +
            "access_token, token_expires_at, personal_sync_enabled, encryption_key_version, created_at, updated_at) " +
            "VALUES (:userId, :email, :calendarId, :refreshToken, :isActive, " +
            "'', NOW(), false, 1, NOW(), NOW()) " +
            "ON DUPLICATE KEY UPDATE " +
            "google_account_email = :email, google_calendar_id = :calendarId, " +
            "refresh_token = :refreshToken, is_active = :isActive, updated_at = NOW()",
            nativeQuery = true)
    void upsert(@Param("userId") Long userId,
                @Param("email") String googleAccountEmail,
                @Param("calendarId") String googleCalendarId,
                @Param("refreshToken") String encryptedRefreshToken,
                @Param("isActive") boolean isActive);

    /**
     * ユーザーIDで接続を無効化する。
     */
    @Modifying
    @Query("UPDATE UserGoogleCalendarConnectionEntity c SET c.isActive = false WHERE c.userId = :userId")
    void deactivate(@Param("userId") Long userId);

    /**
     * 個人スケジュール同期の有効/無効を更新する。
     */
    @Modifying
    @Query("UPDATE UserGoogleCalendarConnectionEntity c SET c.personalSyncEnabled = :isEnabled WHERE c.userId = :userId")
    void updatePersonalSyncEnabled(@Param("userId") Long userId, @Param("isEnabled") boolean isEnabled);
}
