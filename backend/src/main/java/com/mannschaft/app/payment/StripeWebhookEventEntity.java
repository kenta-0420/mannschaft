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
import java.util.UUID;

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

    /** V196 billing 所有投影: 対象 billing_contract（Connect/Platform 以外の billing 由来イベントのみ非 NULL）。 */
    @Column(name = "billing_contract_id", columnDefinition = "BINARY(16)")
    private UUID billingContractId;

    /** V196 billing 所有投影: 対象 billing_customer。 */
    @Column(name = "billing_customer_id", columnDefinition = "BINARY(16)")
    private UUID billingCustomerId;

    /** V196: Stripe オブジェクト参照（invoice/charge/subscription 等の ref）。 */
    @Column(name = "stripe_object_ref", length = 255)
    private String stripeObjectRef;

    /** V196: ペイロードの SHA-256（重複検知・監査用）。 */
    @Column(name = "payload_sha256", length = 64)
    private String payloadSha256;

    /** V196: 処理失敗時刻（リトライ判定に使用）。 */
    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    /** V196: リトライ試行回数。 */
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

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
        if (this.attemptCount == null) {
            this.attemptCount = 0;
        }
    }
}
