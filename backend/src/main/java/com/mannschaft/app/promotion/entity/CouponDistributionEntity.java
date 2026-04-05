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
 * クーポン配布エンティティ。
 */
@Entity
@Table(name = "coupon_distributions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class CouponDistributionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long couponId;

    @Column(nullable = false)
    private Long userId;

    private Long promotionId;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(nullable = false)
    private LocalDateTime distributedAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * 利用済みにする。
     */
    public void markUsed() {
        this.status = "USED";
    }

    /**
     * 期限切れにする。
     */
    public void markExpired() {
        this.status = "EXPIRED";
    }

    /**
     * 取り消す。
     */
    public void revoke() {
        this.status = "REVOKED";
    }

    /**
     * 利用可能かどうかを判定する。
     */
    public boolean isRedeemable() {
        return "ACTIVE".equals(this.status) && this.expiresAt.isAfter(LocalDateTime.now());
    }
}
