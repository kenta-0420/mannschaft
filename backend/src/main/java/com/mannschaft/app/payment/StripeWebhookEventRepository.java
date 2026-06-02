package com.mannschaft.app.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * F22.1 謝礼決済: Webhook 冪等性キーリポジトリ。
 *
 * <p>このフェーズでは Repo 骨格のみ（Service は次陣）。</p>
 */
public interface StripeWebhookEventRepository
        extends JpaRepository<StripeWebhookEventEntity, UUID> {

    /** 同一 event_id が既に受信済みか判定する（冪等性ゲート）。 */
    boolean existsByEventId(String eventId);

    /** event_id から逆引きする。 */
    Optional<StripeWebhookEventEntity> findByEventId(String eventId);
}
