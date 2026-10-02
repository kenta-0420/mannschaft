package com.mannschaft.app.receipt.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 会費支払いドメインから領収書ドメインへ渡す、永続化を伴わないPDF生成データ。 */
public record MemberPaymentReceiptDocument(
        Long memberPaymentId,
        String scopeType,
        Long scopeId,
        Long recipientUserId,
        String recipientName,
        String description,
        BigDecimal amount,
        String paymentMethodLabel,
        LocalDate paymentDate) {
}
