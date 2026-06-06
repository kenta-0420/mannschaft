package com.mannschaft.app.payment.dto;

import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F08.9 P5 第二波: 継続課金レスポンス（設計書 02 §4.1）。camelCase 1:1。
 *
 * <p>PCI 禁則（{@code client_secret} 等）は含めない。{@code stripeSubscriptionId} は運用参照のため返すが
 * カード等の機微情報は持たない（03 §1）。{@code currentPeriodEnd}（期末日）を含め UI に「○月○日まで利用可」を明示する。</p>
 */
@Getter
@Builder
public class MembershipSubscriptionResponse {

    private final UUID id;
    private final Long paymentItemId;
    private final Long beneficiaryUserId;
    private final Long payerUserId;
    private final String scopeKind;
    private final Long scopeId;
    private final String stripeSubscriptionId;
    private final String billingInterval;
    private final Short billingAnchorDay;
    private final String status;
    private final String feePolicyKey;
    private final Integer faceAmount;
    private final String currency;
    private final LocalDate currentPeriodStart;
    private final LocalDate currentPeriodEnd;
    private final Boolean cancelAtPeriodEnd;
    private final LocalDateTime cancelledAt;
    private final LocalDate skipUntil;
    private final LocalDateTime createdAt;

    /**
     * Entity → Response 変換（camelCase 1:1）。
     */
    public static MembershipSubscriptionResponse from(MembershipSubscriptionEntity e) {
        return MembershipSubscriptionResponse.builder()
                .id(e.getId())
                .paymentItemId(e.getPaymentItemId())
                .beneficiaryUserId(e.getBeneficiaryUserId())
                .payerUserId(e.getPayerUserId())
                .scopeKind(e.getScopeKind() != null ? e.getScopeKind().name() : null)
                .scopeId(e.getScopeId())
                .stripeSubscriptionId(e.getStripeSubscriptionId())
                .billingInterval(e.getBillingInterval() != null ? e.getBillingInterval().name() : null)
                .billingAnchorDay(e.getBillingAnchorDay())
                .status(e.getStatus() != null ? e.getStatus().name() : null)
                .feePolicyKey(e.getFeePolicyKey())
                .faceAmount(e.getFaceAmount())
                .currency(e.getCurrency())
                .currentPeriodStart(e.getCurrentPeriodStart())
                .currentPeriodEnd(e.getCurrentPeriodEnd())
                .cancelAtPeriodEnd(e.getCancelAtPeriodEnd())
                .cancelledAt(e.getCancelledAt())
                .skipUntil(e.getSkipUntil())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
