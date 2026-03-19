package com.mannschaft.app.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * パスワードリセットトークンリポジトリ。
 */
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetTokenEntity, Long> {

    Optional<PasswordResetTokenEntity> findByTokenHash(String tokenHash);
}
