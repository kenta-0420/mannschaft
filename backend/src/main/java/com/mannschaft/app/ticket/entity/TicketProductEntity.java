package com.mannschaft.app.ticket.entity;

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
 * 回数券商品エンティティ。チームが販売する回数券の種類を定義する。
 */
@Entity
@Table(name = "ticket_products")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TicketProductEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private Integer totalTickets;

    @Column(nullable = false)
    private Integer price;

    @Column(nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal taxRate = new BigDecimal("10.00");

    private Integer validityDays;

    @Column(length = 100)
    private String stripeProductId;

    @Column(length = 100)
    private String stripePriceId;

    @Column(length = 500)
    private String imageUrl;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isOnlinePurchasable = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * 商品情報を更新する。
     *
     * @param name                商品名
     * @param description         商品説明
     * @param price               価格
     * @param taxRate             消費税率
     * @param validityDays        有効期間（日数）
     * @param isOnlinePurchasable オンライン購入可否
     * @param isActive            販売中かどうか
     * @param sortOrder           表示順
     * @param imageUrl            商品画像URL
     */
    public void update(String name, String description, Integer price, BigDecimal taxRate,
                       Integer validityDays, Boolean isOnlinePurchasable, Boolean isActive,
                       Integer sortOrder, String imageUrl) {
        this.name = name;
        this.description = description;
        this.price = price;
        this.taxRate = taxRate;
        this.validityDays = validityDays;
        this.isOnlinePurchasable = isOnlinePurchasable;
        this.isActive = isActive;
        this.sortOrder = sortOrder;
        this.imageUrl = imageUrl;
    }

    /**
     * Stripe の Product ID と Price ID を設定する。
     *
     * @param stripeProductId Stripe Product ID
     * @param stripePriceId   Stripe Price ID
     */
    public void updateStripeIds(String stripeProductId, String stripePriceId) {
        this.stripeProductId = stripeProductId;
        this.stripePriceId = stripePriceId;
    }

    /**
     * Stripe Price ID のみを差し替える（価格変更時）。
     *
     * @param stripePriceId 新しい Stripe Price ID
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

    /**
     * 税抜価格を算出する。
     *
     * @return 税抜価格（切り捨て）
     */
    public int getPriceExcludingTax() {
        BigDecimal rate = BigDecimal.ONE.add(taxRate.divide(new BigDecimal("100")));
        return new BigDecimal(price).divide(rate, 0, java.math.RoundingMode.FLOOR).intValue();
    }
}
