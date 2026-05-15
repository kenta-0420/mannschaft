package com.mannschaft.app.payment.event;

import com.mannschaft.app.payment.PaymentStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 会費支払いステータス変更イベント。
 *
 * <p>支払いステータスが変化した際に {@code MemberPaymentService} が発行する。
 * F09.15 {@link com.mannschaft.app.succession.service.DelinquencyEscalationListener} が購読し、
 * 滞納開始時（{@link #delinquent} = true）にエスカレーションを生成する。
 *
 * <p>TODO: paymentドメインとsuccessdomainをまたぐ @EventListener 連携。
 * 将来はメッセージキュー（RabbitMQ / Kafka）で分離予定。
 */
public final class PaymentStatusChangedEvent {

    /** 支払いユーザーの ID（弱参照・FK なし）。 */
    private final Long userId;

    /** 変更対象の支払い項目 ID。 */
    private final Long paymentItemId;

    /** 組織 ID。 */
    private final Long organizationId;

    /** 変更前のステータス。 */
    private final PaymentStatus oldStatus;

    /** 変更後のステータス。 */
    private final PaymentStatus newStatus;

    /**
     * 有効期限。
     * {@code valid_until + 猶予期間 < 本日} の場合に {@link #delinquent} = true となる。
     */
    private final LocalDate validUntil;

    /** イベント発生日時。 */
    private final LocalDateTime occurredAt;

    /**
     * 滞納判定フラグ。
     * {@code valid_until + 猶予期間 < 本日} の場合に true となる。
     * このフラグが true の場合のみエスカレーションを生成する。
     */
    private final boolean delinquent;

    public PaymentStatusChangedEvent(
            Long userId,
            Long paymentItemId,
            Long organizationId,
            PaymentStatus oldStatus,
            PaymentStatus newStatus,
            LocalDate validUntil,
            LocalDateTime occurredAt,
            boolean delinquent) {
        this.userId = userId;
        this.paymentItemId = paymentItemId;
        this.organizationId = organizationId;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.validUntil = validUntil;
        this.occurredAt = occurredAt;
        this.delinquent = delinquent;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getPaymentItemId() {
        return paymentItemId;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public PaymentStatus getOldStatus() {
        return oldStatus;
    }

    public PaymentStatus getNewStatus() {
        return newStatus;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public boolean isDelinquent() {
        return delinquent;
    }
}
