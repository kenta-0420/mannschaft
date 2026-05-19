package com.mannschaft.app.payment.repository;

import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
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

    /**
     * 孤児補正バッチ用: 退会済みユーザー（users テーブルに存在しない）の
     * stripe_customers 行を取得する。
     * AccountPurgedEvent 処理漏れ検出・補正のために夜次バッチから呼ぶ。
     */
    @Query(value = """
            SELECT sc.* FROM stripe_customers sc
            LEFT JOIN users u ON sc.user_id = u.id
            WHERE u.id IS NULL
            """, nativeQuery = true)
    List<StripeCustomerEntity> findOrphanStripeCustomers();
}
