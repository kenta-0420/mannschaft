package com.mannschaft.app.payment.connect;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * F22.1 謝礼決済: 受領者（個人/チーム/組織）の Stripe Connect Express アカウント。
 *
 * <p>{@code scope_kind} + {@code scope_id} で受領主体を抽象化する。
 * {@code scope_id}/{@code organization_id} は他ドメインへの論理参照（FKなし・CLAUDE.md 原則1）。
 * F13.1 の BIGINT {@code stripe_connect_accounts} とは別物・流用しない（設計書 §1）。</p>
 *
 * <p>設計書: docs/features/F22.1_market/payment/01_data_model.md §3.1</p>
 */
@Entity
@Table(name = "connect_accounts")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ConnectAccountEntity extends UuidV7Entity {

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_kind", nullable = false, length = 8)
    private ScopeKind scopeKind;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "stripe_account_id", nullable = false, length = 32)
    private String stripeAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "onboarding_status", nullable = false, length = 16)
    private OnboardingStatus onboardingStatus;

    @Column(name = "charges_enabled", nullable = false)
    private Boolean chargesEnabled;

    @Column(name = "payouts_enabled", nullable = false)
    private Boolean payoutsEnabled;

    // === F08.9 P8: 税からくり列（将来の税務確定後に埋める）===

    /**
     * F08.9 P8: 適格請求書登録番号（インボイス制度）。
     * 税務確認後に設定する。現時点では NULL。
     */
    @Column(name = "tax_registration_number", length = 20)
    private String taxRegistrationNumber;

    /**
     * F08.9 P8: 税務ステータス（PENDING / REGISTERED / EXEMPT）。
     * 税務確認後に設定する。現時点では NULL。
     */
    @Column(name = "tax_status", length = 20)
    private String taxStatus;

    @Column(name = "requirements_due", columnDefinition = "JSON")
    private String requirementsDue;

    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Column(name = "default_currency", nullable = false, length = 3)
    private String defaultCurrency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.onboardingStatus == null) {
            this.onboardingStatus = OnboardingStatus.PENDING;
        }
        if (this.chargesEnabled == null) {
            this.chargesEnabled = false;
        }
        if (this.payoutsEnabled == null) {
            this.payoutsEnabled = false;
        }
        if (this.country == null) {
            this.country = "JP";
        }
        if (this.defaultCurrency == null) {
            this.defaultCurrency = "JPY";
        }
    }
}
