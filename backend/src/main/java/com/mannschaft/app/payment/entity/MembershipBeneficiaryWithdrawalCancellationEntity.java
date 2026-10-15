package com.mannschaft.app.payment.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * 受益者退会で DB を先行終端化した後の Stripe 即時取消しを回収する作業行。
 * 退会取消し後も契約自体は復元しないため、Stripe 同期だけを最後まで再試行する。
 */
@Entity
@Table(name = "membership_beneficiary_withdrawal_cancellations",
        uniqueConstraints = @UniqueConstraint(name = "uk_mbwc_subscription", columnNames = "subscription_id"))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class MembershipBeneficiaryWithdrawalCancellationEntity extends UuidV7Entity {

    @Column(name = "subscription_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID subscriptionId;

    @Column(name = "beneficiary_user_id", nullable = false)
    private Long beneficiaryUserId;

    @Column(name = "withdrawal_attempt_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID withdrawalAttemptId;

    @Column(name = "stripe_subscription_id")
    private String stripeSubscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MembershipBeneficiaryWithdrawalCancellationStatus status;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void markSucceeded() {
        this.status = MembershipBeneficiaryWithdrawalCancellationStatus.SUCCEEDED;
        this.lastError = null;
    }

    public void markFailed(String error) {
        // 同じ冪等キーを発行した並行処理の遅い失敗で、先に確定した成功を差し戻さない。
        if (status == MembershipBeneficiaryWithdrawalCancellationStatus.SUCCEEDED) {
            return;
        }
        this.status = MembershipBeneficiaryWithdrawalCancellationStatus.FAILED;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
    }
}
