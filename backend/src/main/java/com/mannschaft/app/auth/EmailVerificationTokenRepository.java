package com.mannschaft.app.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * メール認証トークンリポジトリ。
 */
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationTokenEntity, Long> {

    Optional<EmailVerificationTokenEntity> findByTokenHash(String tokenHash);
}
