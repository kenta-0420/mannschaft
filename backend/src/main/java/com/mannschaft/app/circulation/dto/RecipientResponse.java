package com.mannschaft.app.circulation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 回覧受信者レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class RecipientResponse {

    private final Long id;
    private final Long documentId;
    private final Long userId;
    private final Integer sortOrder;
    private final String status;
    private final LocalDateTime stampedAt;
    private final Long sealId;
    private final String sealVariant;
    private final Short tiltAngle;
    private final Boolean isFlipped;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
