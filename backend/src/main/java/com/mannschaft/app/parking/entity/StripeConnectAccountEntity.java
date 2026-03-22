package com.mannschaft.app.parking.entity;

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
 * Stripe Connect アカウントエンティティ。
 */
@Entity
@Table(name = "stripe_connect_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class StripeConnectAccountEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String stripeAccountId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean chargesEnabled = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean payoutsEnabled = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean onboardingCompleted = false;

    /**
     * Stripe側のステータスを更新する。
     */
    public void updateStripeStatus(Boolean chargesEnabled, Boolean payoutsEnabled, Boolean onboardingCompleted) {
        this.chargesEnabled = chargesEnabled;
        this.payoutsEnabled = payoutsEnabled;
        this.onboardingCompleted = onboardingCompleted;
    }
}
