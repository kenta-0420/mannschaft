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

    @Mapping(target = "type", expression = "java(entity.getType().name())")
    PaymentItemResponse toPaymentItemResponse(PaymentItemEntity entity);

    List<PaymentItemResponse> toPaymentItemResponseList(List<PaymentItemEntity> entities);

    @Mapping(target = "paymentMethod", expression = "java(entity.getPaymentMethod().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    MemberPaymentResponse toMemberPaymentResponse(MemberPaymentEntity entity);

    List<MemberPaymentResponse> toMemberPaymentResponseList(List<MemberPaymentEntity> entities);
}
