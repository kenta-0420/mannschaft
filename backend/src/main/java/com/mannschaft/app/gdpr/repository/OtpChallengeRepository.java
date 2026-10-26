package com.mannschaft.app.gdpr.repository;

import com.mannschaft.app.gdpr.entity.OtpChallengeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * OTPチャレンジリポジトリ。
 */
public interface OtpChallengeRepository extends JpaRepository<OtpChallengeEntity, Long> {

    /**
     * 最新の有効なOTPを取得する（used_at IS NULL かつ expires_at > now）。
     */
    Optional<OtpChallengeEntity> findTopByUserIdAndPurposeAndUsedAtIsNullOrderByCreatedAtDesc(Long userId, String purpose);

    /**
     * 期限切れOTPを削除する（クリーンアップ用）。
     */
    @Modifying
    @Query("DELETE FROM OtpChallengeEntity o WHERE o.expiresAt < :now")
    int deleteExpiredChallenges(@Param("now") LocalDateTime now);
}
