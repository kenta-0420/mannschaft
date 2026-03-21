package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.UserIcalTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * iCal購読URLトークンリポジトリ。
 */
public interface UserIcalTokenRepository extends JpaRepository<UserIcalTokenEntity, Long> {

    /**
     * ユーザーIDでトークンを取得する。
     */
    Optional<UserIcalTokenEntity> findByUserId(Long userId);

    /**
     * トークン文字列でアクティブなトークンを取得する。
     */
    Optional<UserIcalTokenEntity> findByTokenAndIsActiveTrue(String token);

    /**
     * トークン文字列の存在確認を行う。
     */
    boolean existsByToken(String token);

    /**
     * トークン文字列でトークンを取得する。
     */
    Optional<UserIcalTokenEntity> findByToken(String token);

    /**
     * 新規トークンを挿入する。
     */
    @Modifying
    @Query(value = "INSERT INTO user_ical_tokens (user_id, token, is_active, created_at, updated_at) " +
            "VALUES (:userId, :token, :isActive, NOW(), NOW())",
            nativeQuery = true)
    void insert(@Param("userId") Long userId,
                @Param("token") String token,
                @Param("isActive") boolean isActive);

    /**
     * ユーザーIDでトークン文字列を更新する。
     */
    @Modifying
    @Query("UPDATE UserIcalTokenEntity t SET t.token = :newToken WHERE t.userId = :userId")
    void updateToken(@Param("userId") Long userId, @Param("newToken") String newToken);

    /**
     * ユーザーIDでトークンを削除する。
     */
    void deleteByUserId(Long userId);

    /**
     * トークンのポーリング日時を更新する。
     */
    @Modifying
    @Query("UPDATE UserIcalTokenEntity t SET t.lastPolledAt = :lastPolledAt WHERE t.token = :token")
    void updateLastPolledAt(@Param("token") String token, @Param("lastPolledAt") LocalDateTime lastPolledAt);
}
