package com.mannschaft.app.webhook.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.webhook.DeliveryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Webhook配信ログエンティティ。
 * 論理削除なし。
 */
@Entity
@Table(name = "webhook_delivery_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class WebhookDeliveryLogEntity extends BaseEntity {

    @Column(nullable = false)
    private Long endpointId;

    @Column(nullable = false, length = 100)
    private String eventType;

    @Column(nullable = false, length = 36)
    private String eventId;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String requestPayload;

    private Integer responseStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryStatus deliveryStatus;

    @Column(nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;
}
