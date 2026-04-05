package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.OAuthLinkTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * OAuthアカウント連携トークンリポジトリ。
 */
public interface OAuthLinkTokenRepository extends JpaRepository<OAuthLinkTokenEntity, Long> {

    Optional<OAuthLinkTokenEntity> findByTokenHash(String tokenHash);
}
