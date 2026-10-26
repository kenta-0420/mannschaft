package com.mannschaft.app.payment;

import com.mannschaft.app.payment.dto.MemberPaymentResponse;
import com.mannschaft.app.payment.dto.PaymentItemResponse;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * 支払い管理機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface PaymentMapper {

    @Mapping(target = "meta", expression = "java(new com.mannschaft.app.payment.dto.PaymentItemResponse.PaymentItemMetaDto(entity.getName(), entity.getDescription(), entity.getType() != null ? entity.getType().name() : null, entity.getDisplayOrder(), entity.getGracePeriodDays()))")
    @Mapping(target = "money", expression = "java(new com.mannschaft.app.payment.dto.PaymentItemResponse.PaymentMoneyDto(entity.getAmount(), entity.getCurrency()))")
    @Mapping(target = "stripe", expression = "java(new com.mannschaft.app.payment.dto.PaymentItemResponse.StripeIntegrationDto(entity.getStripeProductId(), entity.getStripePriceId()))")
    @Mapping(target = "audit", expression = "java(new com.mannschaft.app.payment.dto.PaymentItemResponse.PaymentItemAuditDto(entity.getIsActive(), entity.getCreatedAt(), entity.getUpdatedAt()))")
    @Mapping(target = "term", expression = "java(entity.getTermStartsOn() != null || entity.getTermEndsOn() != null ? new com.mannschaft.app.payment.dto.PaymentItemResponse.TermPeriodDto(entity.getTermStartsOn(), entity.getTermEndsOn()) : null)")
    PaymentItemResponse toPaymentItemResponse(PaymentItemEntity entity);

    List<PaymentItemResponse> toPaymentItemResponseList(List<PaymentItemEntity> entities);

    @Mapping(target = "userName", ignore = true)
    @Mapping(target = "paymentMethod", expression = "java(entity.getPaymentMethod() != null ? entity.getPaymentMethod().name() : null)")
    @Mapping(target = "money", expression = "java(new com.mannschaft.app.payment.dto.MemberPaymentResponse.PaymentMoneyDto(entity.getAmountPaid(), entity.getCurrency()))")
    @Mapping(target = "statusInfo", expression = "java(new com.mannschaft.app.payment.dto.MemberPaymentResponse.PaymentStatusDto(entity.getStatus() != null ? entity.getStatus().name() : null, entity.getValidFrom(), entity.getValidUntil(), entity.getPaidAt()))")
    @Mapping(target = "refund", expression = "java(new com.mannschaft.app.payment.dto.MemberPaymentResponse.PaymentRefundDto(entity.getStripeRefundId(), entity.getStripeReceiptUrl(), entity.getRefundedAt()))")
    @Mapping(target = "audit", expression = "java(new com.mannschaft.app.payment.dto.MemberPaymentResponse.PaymentAuditDto(entity.getNote(), entity.getCreatedAt(), entity.getUpdatedAt()))")
    MemberPaymentResponse toMemberPaymentResponse(MemberPaymentEntity entity);

    List<MemberPaymentResponse> toMemberPaymentResponseList(List<MemberPaymentEntity> entities);
}
