package com.mannschaft.app.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * F22.1 謝礼決済: Webhook 冪等性キーリポジトリ。
 *
 * <p>受信イベントの冪等記録は webhook 処理系から利用される。</p>
 */
public interface StripeWebhookEventRepository
        extends JpaRepository<StripeWebhookEventEntity, UUID> {

    /** 同一 event_id が既に受信済みか判定する（冪等性ゲート）。 */
    boolean existsByEventId(String eventId);

    /** event_id から逆引きする。 */
    Optional<StripeWebhookEventEntity> findByEventId(String eventId);

    /**
     * F20.1 PR5: charge → billing 所有の対応を、受信記録から辿る（dispute の invoice 解決に使う）。
     */
    Optional<StripeWebhookEventEntity>
            findFirstByStripeObjectRefAndBillingCustomerIdIsNotNullOrderByReceivedAtDesc(String stripeObjectRef);
}
