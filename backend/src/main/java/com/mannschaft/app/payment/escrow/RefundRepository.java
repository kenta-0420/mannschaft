package com.mannschaft.app.payment.escrow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F22.1 謝礼決済: 返金記録リポジトリ。
 *
 * <p>このフェーズでは Repo 骨格のみ（Service は次陣）。</p>
 */
public interface RefundRepository extends JpaRepository<RefundEntity, UUID> {

    /** 取引に紐づく返金記録を取得する。 */
    List<RefundEntity> findByEscrowTransactionId(UUID escrowTransactionId);

    /** Stripe Refund ID（re_xxx）から逆引きする（Webhook ハンドラ用）。 */
    Optional<RefundEntity> findByStripeRefundId(String stripeRefundId);
}
