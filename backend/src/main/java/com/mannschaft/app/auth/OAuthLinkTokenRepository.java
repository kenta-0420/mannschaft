package com.mannschaft.app.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * OAuthアカウント連携トークンリポジトリ。
 */
public interface OAuthLinkTokenRepository extends JpaRepository<OAuthLinkTokenEntity, Long> {

    Optional<OAuthLinkTokenEntity> findByTokenHash(String tokenHash);
}
