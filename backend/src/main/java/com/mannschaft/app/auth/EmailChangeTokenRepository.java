package com.mannschaft.app.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * メールアドレス変更トークンリポジトリ。
 */
public interface EmailChangeTokenRepository extends JpaRepository<EmailChangeTokenEntity, Long> {

    Optional<EmailChangeTokenEntity> findByTokenHash(String tokenHash);
}
