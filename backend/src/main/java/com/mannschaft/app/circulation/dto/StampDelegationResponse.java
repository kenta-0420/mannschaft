package com.mannschaft.app.circulation.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 押印委任レスポンス。
 */
public record StampDelegationResponse(
        UUID id,
        Long documentId,
        Long delegatorUserId,
        Long delegateeUserId,
        String reason,
        String status,
        LocalDateTime createdAt
) {
}
