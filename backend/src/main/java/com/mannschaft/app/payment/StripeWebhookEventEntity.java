package com.mannschaft.app.payment;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * F22.1 謝礼決済: Webhook 冪等性キー。
 *
 * <p>同一 {@code event_id} の二重処理を UNIQUE 制約で物理拒否する（冪等性ゲート）。
 * Connect/Platform 両 Webhook の再送（at-least-once）を冪等化する共通テーブル。</p>
 *
 * <p>受信イベントの冪等記録は webhook 処理系が担う。</p>
 *
 * <p>設計書: docs/features/F22.1_market/payment/01_data_model.md §3.5</p>
 */
@Entity
@Table(name = "stripe_webhook_events")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class StripeWebhookEventEntity extends UuidV7Entity {

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "type", nullable = false, length = 64)
    private String type;

    @Column(name = "livemode", nullable = false)
    private Boolean livemode;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "process_status", nullable = false, length = 12)
    private WebhookProcessStatus processStatus;

    @PrePersist
    protected void onCreate() {
        if (this.receivedAt == null) {
            this.receivedAt = LocalDateTime.now();
        }
        if (this.livemode == null) {
            this.livemode = false;
        }
        if (this.processStatus == null) {
            this.processStatus = WebhookProcessStatus.RECEIVED;
        }
    }
}
