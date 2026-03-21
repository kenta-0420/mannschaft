package com.mannschaft.app.payment.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.payment.PaymentItemType;
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
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支払い項目エンティティ。チーム/組織ごとに ADMIN が作成する支払い定義を管理する。
 */
@Entity
@Table(name = "payment_items")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class PaymentItemEntity extends BaseEntity {

    private Long teamId;

    private Long organizationId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentItemType type;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "JPY";

    @Column(length = 100)
    private String stripeProductId;

    @Column(length = 100)
    private String stripePriceId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(nullable = false)
    @Builder.Default
    private Short displayOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private Short gracePeriodDays = 0;

    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * 支払い項目の基本情報を更新する。type は変更不可。
     */
    public void update(String name, String description, BigDecimal amount, String currency,
                       Boolean isActive, Short displayOrder, Short gracePeriodDays) {
        if (name != null) this.name = name;
        if (description != null) this.description = description;
        if (amount != null) this.amount = amount;
        if (currency != null) this.currency = currency;
        if (isActive != null) this.isActive = isActive;
        if (displayOrder != null) this.displayOrder = displayOrder;
        if (gracePeriodDays != null) this.gracePeriodDays = gracePeriodDays;
    }

    /**
     * Stripe Product/Price ID を設定する。
     */
    public void updateStripeIds(String stripeProductId, String stripePriceId) {
        this.stripeProductId = stripeProductId;
        this.stripePriceId = stripePriceId;
    }

    /**
     * Stripe Price ID のみ更新する（金額変更時の Price 差し替え用）。
     */
    public void updateStripePriceId(String stripePriceId) {
        this.stripePriceId = stripePriceId;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
