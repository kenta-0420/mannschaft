package com.mannschaft.app.payment.dto;

import com.mannschaft.app.payment.PaymentRequestStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.PaymentRequestEntity;
import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F08.9 P7: 協会→加盟チーム請求のレスポンス DTO（02_api §9）。
 *
 * <p>casing は camelCase で Entity と 1:1。協会視点（発行一覧）/チーム視点（受信一覧・詳細）の双方で使う。
 * 着金口座 ID 等の内部キーは露出せず、状態・額面・期限・タイムスタンプの業務情報のみ返す。</p>
 */
@Builder
public record PaymentRequestResponse(
        UUID id,
        Long organizationId,
        ScopeKind issuerScopeKind,
        Long issuerScopeId,
        ScopeKind payerScopeKind,
        Long payerScopeId,
        String title,
        String description,
        Integer faceAmount,
        String currency,
        String taxCategory,
        LocalDate dueDate,
        PaymentRequestStatus status,
        UUID escrowTransactionId,
        Long confirmableNotificationId,
        UUID supersededById,
        LocalDateTime sentAt,
        LocalDateTime viewedAt,
        LocalDateTime paidAt,
        LocalDateTime createdAt) {

    /**
     * Entity を DTO へ写像する。
     */
    public static PaymentRequestResponse from(PaymentRequestEntity e) {
        return PaymentRequestResponse.builder()
                .id(e.getId())
                .organizationId(e.getOrganizationId())
                .issuerScopeKind(e.getIssuerScopeKind())
                .issuerScopeId(e.getIssuerScopeId())
                .payerScopeKind(e.getPayerScopeKind())
                .payerScopeId(e.getPayerScopeId())
                .title(e.getTitle())
                .description(e.getDescription())
                .faceAmount(e.getFaceAmount())
                .currency(e.getCurrency())
                .taxCategory(e.getTaxCategory())
                .dueDate(e.getDueDate())
                .status(e.getStatus())
                .escrowTransactionId(e.getEscrowTransactionId())
                .confirmableNotificationId(e.getConfirmableNotificationId())
                .supersededById(e.getSupersededById())
                .sentAt(e.getSentAt())
                .viewedAt(e.getViewedAt())
                .paidAt(e.getPaidAt())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
