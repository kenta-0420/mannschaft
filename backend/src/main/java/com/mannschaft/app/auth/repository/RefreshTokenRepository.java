package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * リフレッシュトークンリポジトリ。
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    List<RefreshTokenEntity> findByUserIdAndRevokedAtIsNull(Long userId);

    void deleteByExpiresAtBefore(LocalDateTime dateTime);

    /**
     * 指定ユーザーの直近N日以内に同一IP+デバイスフィンガープリントのトークンが存在するか確認する。
     * 新規デバイスログイン検知に使用（F12.4 §5.5）。
     */
    boolean existsByUserIdAndIpAddressAndDeviceFingerprintAndCreatedAtAfter(
            Long userId, String ipAddress, String deviceFingerprint, LocalDateTime since);

    /**
     * 指定ユーザーのアクティブセッション数を返す（セッション上限チェック用、F12.4 §5.7）。
     */
    long countByUserIdAndRevokedAtIsNullAndExpiresAtAfter(Long userId, LocalDateTime now);

    /**
     * 指定ユーザーのトークン総数を返す（初回ログイン判定用、F12.4 §5.5）。
     */
    long countByUserId(Long userId);
}
