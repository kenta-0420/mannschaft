package com.mannschaft.app.payment.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.payment.PaymentProxyGrantStatus;
import com.mannschaft.app.payment.PaymentProxyGrantedVia;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * F08.9 第三者代理払い許可エンティティ。
 *
 * <p>非後見の第三者（祖父母・スポンサー等）が受益者の会費を払うための明示許諾を管理する。
 * 保護者（後見）経由の代理払いは {@code parental_consent_links} / {@code user_care_links} を
 * 参照するため本テーブル不要。本テーブルは非後見の第三者払い専用。</p>
 *
 * <p>状態遷移:
 * {@code PENDING}（招待発行）→ {@code ACTIVE}（払い手が受諾）→
 * {@code REVOKED}（受益者/払い手が取消）または {@code EXPIRED}（effective_until 超過・日次バッチ）。</p>
 *
 * <p>設計原則:</p>
 * <ul>
 *   <li>原則1: クロスドメイン FK なし（beneficiary_user_id / payer_user_id / payment_item_id はすべて論理参照）。</li>
 *   <li>原則6: 主キーは UUIDv7（{@link UuidV7Entity} 継承）。</li>
 *   <li>原則7: organization_id を持つため {@code AbstractTenantAwareRepository} 継承対象。</li>
 * </ul>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §2.3</p>
 */
@Entity
@Table(name = "payment_proxy_grants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class PaymentProxyGrantEntity extends UuidV7Entity {

    /**
     * テナント（シャードキー候補）。論理参照・FK なし。
     * NULL は組織横断 grant（通常は NULL にならない）。
     */
    @Column(name = "organization_id")
    private Long organizationId;

    /**
     * 受益者ユーザーID（許可を出す側）。論理参照・FK なし。
     */
    @Column(name = "beneficiary_user_id", nullable = false)
    private Long beneficiaryUserId;

    /**
     * 払い手ユーザーID（許可される側）。論理参照・FK なし。
     */
    @Column(name = "payer_user_id", nullable = false)
    private Long payerUserId;

    /**
     * 用途。現在は {@code PAYMENT} 固定。
     */
    @Column(name = "scope", nullable = false, length = 16)
    @Builder.Default
    private String scope = "PAYMENT";

    /**
     * 特定項目限定の場合の payment_items.id。
     * NULL の場合は受益者の全会費を対象とする「包括 grant」。
     * 包括 grant は effective_until が必須（CHECK 制約）。
     */
    @Column(name = "payment_item_id")
    private Long paymentItemId;

    /**
     * 1 回あたり支払い上限（円整数）。NULL の場合は上限なし（濫用抑止用）。
     */
    @Column(name = "max_amount")
    private Integer maxAmount;

    /**
     * 許可のステータス。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    @Builder.Default
    private PaymentProxyGrantStatus status = PaymentProxyGrantStatus.PENDING;

    /**
     * grant 有効開始日時（UTC）。
     */
    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    /**
     * grant 有効終了日時（UTC）。NULL の場合は無期限（取消まで）。
     * 包括 grant（paymentItemId=NULL）は NOT NULL 必須（DDL の CHECK 制約で強制）。
     */
    @Column(name = "effective_until")
    private LocalDateTime effectiveUntil;

    /**
     * 権原発行経路（INVITE_TOKEN / IN_APP）。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "granted_via", nullable = false, length = 16)
    private PaymentProxyGrantedVia grantedVia;

    /**
     * REVOKED 遷移日時（UTC）。REVOKED の場合のみ設定。
     */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /**
     * 取消操作者ユーザーID（論理参照・FK なし）。REVOKED の場合のみ設定。
     */
    @Column(name = "revoked_by")
    private Long revokedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 論理削除（GDPR/退会）。テナント基底 {@code AbstractTenantAwareRepository} の deleted_at 規約に対応。
     * 業務状態（status=REVOKED/EXPIRED）とは独立。NULL=有効。
     */
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
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * grant を ACTIVE 状態に遷移する（払い手が受諾）。
     */
    public void activate() {
        this.status = PaymentProxyGrantStatus.ACTIVE;
    }

    /**
     * grant を REVOKED 状態に遷移する（受益者または払い手が取消）。
     *
     * @param revokedByUserId 取消操作者のユーザーID
     */
    public void revoke(Long revokedByUserId) {
        this.status = PaymentProxyGrantStatus.REVOKED;
        this.revokedAt = LocalDateTime.now();
        this.revokedBy = revokedByUserId;
    }

    /**
     * grant を EXPIRED 状態に遷移する（日次バッチが effective_until 超過を検知して更新）。
     */
    public void expire() {
        this.status = PaymentProxyGrantStatus.EXPIRED;
    }
}
