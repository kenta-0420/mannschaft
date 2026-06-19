package com.mannschaft.app.payment.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.payment.AdvanceSettlementStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F08.9 協会請求の立替/精算記録エンティティ（team_payment_advances・案3）。
 *
 * <p>協会→チーム請求（payment_requests）を「チーム ADMIN 個人の Stripe Customer で立替課金」（案3・
 * README §6.3）した事実と、後にチームから精算された事実を記録する。協会請求支払い時に {@code PENDING} で
 * 起票し、F04.9 確認必須通知で精算確認 → {@code SETTLED}。</p>
 *
 * <p>設計原則:</p>
 * <ul>
 *   <li>原則1: クロスドメイン FK なし（team/user/escrow/payment_request はすべて論理参照）。</li>
 *   <li>原則6: 主キーは UUIDv7（{@link UuidV7Entity} 継承）。</li>
 *   <li>原則7: organization_id を持つため {@code AbstractTenantAwareRepository} 継承対象。</li>
 * </ul>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §2.5 / 02_api_design.md §7</p>
 */
@Entity
@Table(name = "team_payment_advances")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class TeamPaymentAdvanceEntity extends UuidV7Entity {

    /** テナント（シャードキー候補）。論理参照・FK なし。 */
    @Column(name = "organization_id")
    private Long organizationId;

    /** 立替の主体チーム。論理参照・FK なし。 */
    @Column(name = "team_id", nullable = false)
    private Long teamId;

    /** 立替えた ADMIN 個人。論理参照・FK なし。 */
    @Column(name = "payer_user_id", nullable = false)
    private Long payerUserId;

    /** F22.1 money rail への連結。論理参照・FK なし。 */
    @Column(name = "escrow_transaction_id")
    private UUID escrowTransactionId;

    /** 対象の協会請求。論理参照・FK なし。1請求＝1立替（UNIQUE）。 */
    @Column(name = "payment_request_id")
    private UUID paymentRequestId;

    /** 立替額（円整数・払い手が課金された請求額）。 */
    @Column(name = "advanced_amount", nullable = false)
    private Integer advancedAmount;

    /** 通貨。 */
    @Column(name = "currency", nullable = false, length = 3)
    @SuperBuilder.Default
    private String currency = "JPY";

    /** 立替（協会請求支払い）日時。 */
    @Column(name = "advanced_at", nullable = false)
    private LocalDateTime advancedAt;

    /** チームからの精算状態。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_status", nullable = false, length = 12)
    @SuperBuilder.Default
    private AdvanceSettlementStatus settlementStatus = AdvanceSettlementStatus.PENDING;

    /** 精算完了日時。SETTLED の場合のみ設定。 */
    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    /** 精算を確認した者（チーム ADMIN・F04.9 確認）。論理参照・FK なし。SETTLED の場合のみ設定。 */
    @Column(name = "settled_confirmed_by")
    private Long settledConfirmedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 論理削除（GDPR/退会）。業務状態（settlement_status）とは独立。NULL=有効。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.advancedAt == null) {
            this.advancedAt = now;
        }
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
     * 精算確認で SETTLED 状態に遷移する（チーム ADMIN が立替金の返金を確認）。
     *
     * @param confirmedByUserId 精算を確認したチーム ADMIN のユーザーID
     */
    public void markAsSettled(Long confirmedByUserId) {
        this.settlementStatus = AdvanceSettlementStatus.SETTLED;
        this.settledAt = LocalDateTime.now();
        this.settledConfirmedBy = confirmedByUserId;
    }
}
