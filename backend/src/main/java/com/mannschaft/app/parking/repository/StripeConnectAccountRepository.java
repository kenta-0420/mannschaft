package com.mannschaft.app.parking.repository;

import com.mannschaft.app.parking.entity.StripeConnectAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Stripe Connect アカウントリポジトリ。
 */
public interface StripeConnectAccountRepository extends JpaRepository<StripeConnectAccountEntity, Long> {

    Optional<StripeConnectAccountEntity> findByUserId(Long userId);

    Optional<StripeConnectAccountEntity> findByStripeAccountId(String stripeAccountId);
}
