package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.TwoFactorAuthEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 二要素認証リポジトリ。
 */
public interface TwoFactorAuthRepository extends JpaRepository<TwoFactorAuthEntity, Long> {

    Optional<TwoFactorAuthEntity> findByUserId(Long userId);
}
