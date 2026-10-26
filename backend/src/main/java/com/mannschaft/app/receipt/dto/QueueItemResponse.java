package com.mannschaft.app.receipt.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * キューアイテムレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class QueueItemResponse {
    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final Long memberPaymentId;
    private final Long recipientUserId;
    private final String suggestedDescription;
    private final BigDecimal suggestedAmount;
    private final Long presetId;
    private final String status;
    private final Long processedReceiptId;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
