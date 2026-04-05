package com.mannschaft.app.webhook.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Webhookイベントサブスクリプションエンティティ。
 * 論理削除なし。
 */
@Entity
@Table(name = "webhook_event_subscriptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class WebhookEventSubscriptionEntity extends BaseEntity {

    @Column(nullable = false)
    private Long endpointId;

    @Column(nullable = false, length = 100)
    private String eventType;
}
