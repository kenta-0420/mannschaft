package com.mannschaft.app.payment.repository;

import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Stripe 顧客リポジトリ。
 */
public interface StripeCustomerRepository extends JpaRepository<StripeCustomerEntity, Long> {

    /**
     * ユーザー ID で Stripe Customer を取得する。
     */
    Optional<StripeCustomerEntity> findByUserId(Long userId);

    /**
     * Stripe Customer ID で取得する。
     */
    Optional<StripeCustomerEntity> findByStripeCustomerId(String stripeCustomerId);
}
