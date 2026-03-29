package com.mannschaft.app.advertising.entity;

import com.mannschaft.app.advertising.AdvertiserAccountStatus;
import com.mannschaft.app.advertising.BillingMethod;
import com.mannschaft.app.common.BaseEntity;
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
 * 広告主アカウントエンティティ。
 */
@Entity
@Table(name = "advertiser_accounts")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AdvertiserAccountEntity extends BaseEntity {

    @Column(nullable = false)
    private Long organizationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AdvertiserAccountStatus status = AdvertiserAccountStatus.PENDING;

    @Column(nullable = false, length = 200)
    private String companyName;

    @Column(nullable = false, length = 254)
    private String contactEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private BillingMethod billingMethod = BillingMethod.STRIPE;

    @Column(length = 50)
    private String stripeCustomerId;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal creditLimit = new BigDecimal("100000");

    private Long approvedBy;

    private LocalDateTime approvedAt;

    private LocalDateTime deletedAt;

    /**
     * アカウントを承認する（PENDING → ACTIVE）。
     */
    public void approve(Long approvedByUserId) {
        if (this.status != AdvertiserAccountStatus.PENDING) {
            throw new IllegalStateException("承認はPENDING状態のアカウントのみ可能です");
        }
        this.status = AdvertiserAccountStatus.ACTIVE;
        this.approvedBy = approvedByUserId;
        this.approvedAt = LocalDateTime.now();
    }

    /**
     * アカウントを停止する（ACTIVE → SUSPENDED）。
     */
    public void suspend() {
        if (this.status != AdvertiserAccountStatus.ACTIVE) {
            throw new IllegalStateException("停止はACTIVE状態のアカウントのみ可能です");
        }
        this.status = AdvertiserAccountStatus.SUSPENDED;
    }

    /**
     * アカウントを再有効化する（SUSPENDED → ACTIVE）。
     */
    public void reactivate(Long approvedByUserId) {
        if (this.status != AdvertiserAccountStatus.SUSPENDED) {
            throw new IllegalStateException("再有効化はSUSPENDED状態のアカウントのみ可能です");
        }
        this.status = AdvertiserAccountStatus.ACTIVE;
        this.approvedBy = approvedByUserId;
        this.approvedAt = LocalDateTime.now();
    }

    /**
     * プロフィール情報を更新する。
     */
    public void updateProfile(String companyName, String contactEmail) {
        this.companyName = companyName;
        this.contactEmail = contactEmail;
    }

    /**
     * Stripe Customer IDを設定する。
     */
    public void assignStripeCustomerId(String stripeCustomerId) {
        this.stripeCustomerId = stripeCustomerId;
    }

    /**
     * 与信限度額を更新する。
     */
    public void updateCreditLimit(BigDecimal newLimit) {
        this.creditLimit = newLimit;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
