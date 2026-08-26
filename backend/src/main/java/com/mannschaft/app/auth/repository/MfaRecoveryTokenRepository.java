package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.MfaRecoveryTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Optional;

/**
 * MFAリカバリートークンリポジトリ。
 */
public interface MfaRecoveryTokenRepository extends JpaRepository<MfaRecoveryTokenEntity, Long> {

    Optional<MfaRecoveryTokenEntity> findByTokenHash(String tokenHash);

    /**
     * 指定ユーザーのMFAリカバリートークンを全件物理削除する（GDPR退会バッチ用）。
     *
     * @param userId 削除対象ユーザーID
     */
    @Modifying
    void deleteByUserId(Long userId);
}
