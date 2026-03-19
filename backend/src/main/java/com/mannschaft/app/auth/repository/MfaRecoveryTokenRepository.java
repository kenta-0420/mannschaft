package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.MfaRecoveryTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * MFAリカバリートークンリポジトリ。
 */
public interface MfaRecoveryTokenRepository extends JpaRepository<MfaRecoveryTokenEntity, Long> {

    Optional<MfaRecoveryTokenEntity> findByTokenHash(String tokenHash);
}
