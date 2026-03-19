package com.mannschaft.app.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * MFAリカバリートークンリポジトリ。
 */
public interface MfaRecoveryTokenRepository extends JpaRepository<MfaRecoveryTokenEntity, Long> {

    Optional<MfaRecoveryTokenEntity> findByTokenHash(String tokenHash);
}
