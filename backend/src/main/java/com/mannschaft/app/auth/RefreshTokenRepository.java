package com.mannschaft.app.auth;

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
}
