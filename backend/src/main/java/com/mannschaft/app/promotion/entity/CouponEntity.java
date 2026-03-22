package com.mannschaft.app.promotion.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * クーポンエンティティ。
 */
@Entity
@Table(name = "coupons")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class CouponEntity extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false)
    private Long createdBy;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 20)
    private String couponType;

    private BigDecimal discountValue;

    private BigDecimal minPurchaseAmount;

    private Integer maxIssues;

    @Column(nullable = false)
    @Builder.Default
    private Integer issuedCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Short maxUsesPerUser = 1;

    @Column(nullable = false)
    private LocalDateTime validFrom;

    @Column(nullable = false)
    private LocalDateTime validUntil;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    private LocalDateTime deletedAt;

    /**
     * クーポン情報を更新する。
     */
    public void update(String title, String description, String couponType,
                       BigDecimal discountValue, BigDecimal minPurchaseAmount,
                       Integer maxIssues, Short maxUsesPerUser,
                       LocalDateTime validFrom, LocalDateTime validUntil) {
        this.title = title;
        this.description = description;
        this.couponType = couponType;
        this.discountValue = discountValue;
        this.minPurchaseAmount = minPurchaseAmount;
        this.maxIssues = maxIssues;
        this.maxUsesPerUser = maxUsesPerUser;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
    }

    /**
     * 有効/無効を切り替える。
     */
    public void toggleActive() {
        this.isActive = !this.isActive;
    }

    /**
     * 発行数をインクリメントする。
     */
    public void incrementIssuedCount() {
        this.issuedCount++;
    }

    /**
     * 発行上限に達しているかどうかを判定する。
     */
    public boolean isIssueLimitReached() {
        return this.maxIssues != null && this.issuedCount >= this.maxIssues;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
