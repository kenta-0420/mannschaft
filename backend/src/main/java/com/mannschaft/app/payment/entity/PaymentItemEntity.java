package com.mannschaft.app.payment.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.payment.BillingInterval;
import com.mannschaft.app.payment.PaymentItemType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 支払い項目エンティティ。チーム/組織ごとに ADMIN が作成する支払い定義を管理する。
 */
@Entity
@Table(name = "payment_items")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
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
    @SuperBuilder.Default
    private String currency = "JPY";

    @Column(length = 100)
    private String stripeProductId;

    @Column(length = 100)
    private String stripePriceId;

    @Column(nullable = false)
    @SuperBuilder.Default
    private Boolean isActive = true;

    @Column(nullable = false)
    @SuperBuilder.Default
    private Short displayOrder = 0;

    @Column(nullable = false)
    @SuperBuilder.Default
    private Short gracePeriodDays = 0;

    /**
     * F08.9 P5: 継続課金（Stripe Subscription 管理）か。TRUE の項目は P5 で Subscription を作成する対象。
     * 後方互換: 既定 FALSE で現挙動と完全一致（設計書 01 §1.2）。
     */
    @Column(name = "is_recurring", nullable = false)
    @SuperBuilder.Default
    private Boolean isRecurring = false;

    /**
     * F08.9 P5: 課金周期（MONTHLY/YEARLY）。{@code isRecurring=TRUE} 時に必須・それ以外は NULL。
     * {@link PaymentItemType#MONTHLY_FEE}/{@link PaymentItemType#ANNUAL_FEE} と整合（設計書 01 §1.2）。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "billing_interval", length = 8)
    private BillingInterval billingInterval;

    /**
     * F08.9 P6: 期別有効開始日（{@link PaymentItemType#TERM} のみ使用）。
     */
    @Column(name = "term_starts_on")
    private LocalDate termStartsOn;

    /**
     * F08.9 P6: 期別有効終了日（{@link PaymentItemType#TERM} のみ使用）。
     * TERM 型のチェックアウト完了時に {@code member_payments.valid_until} として設定される。
     */
    @Column(name = "term_ends_on")
    private LocalDate termEndsOn;

    // === F08.9 P8: 税からくり列（将来の国別TaxPolicy実装まで null のまま）===

    /**
     * F08.9 P8: 税区分（例: STANDARD_10 / REDUCED_8 / EXEMPT）。
     * 税理士確認後に設定する。現時点では NULL。
     */
    @Column(name = "tax_category", length = 30)
    private String taxCategory;

    /**
     * F08.9 P8: 税率（0.1000=10%）。
     * 税理士確認後に設定する。現時点では NULL。
     */
    @Column(name = "tax_rate", precision = 5, scale = 4)
    private BigDecimal taxRate;

    /**
     * F08.9 P8: 税込み価格フラグ。TRUE の場合 amount は税込み価格を示す。
     * 税理士確認後に設定する。現時点では NULL。
     */
    @Column(name = "price_includes_tax")
    private Boolean priceIncludesTax;

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
