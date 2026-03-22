package com.mannschaft.app.promotion.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * プロモーション配信エンティティ。
 */
@Entity
@Table(name = "promotion_deliveries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class PromotionDeliveryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long promotionId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String channel;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    private LocalDateTime deliveredAt;

    private LocalDateTime openedAt;

    @Column(length = 200)
    private String failedReason;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * 配信済みにする。
     */
    public void markDelivered() {
        this.status = "DELIVERED";
        this.deliveredAt = LocalDateTime.now();
    }

    /**
     * 開封済みにする。
     */
    public void markOpened() {
        this.status = "OPENED";
        this.openedAt = LocalDateTime.now();
    }

    /**
     * 配信失敗にする。
     */
    public void markFailed(String reason) {
        this.status = "FAILED";
        this.failedReason = reason;
    }
}
