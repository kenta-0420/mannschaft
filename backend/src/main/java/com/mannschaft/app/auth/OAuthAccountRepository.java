package com.mannschaft.app.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * OAuth連携アカウントリポジトリ。
 */
public interface OAuthAccountRepository extends JpaRepository<OAuthAccountEntity, Long> {

    Optional<OAuthAccountEntity> findByProviderAndProviderUserId(
            OAuthAccountEntity.OAuthProvider provider, String providerUserId);

    List<OAuthAccountEntity> findByUserId(Long userId);
}
