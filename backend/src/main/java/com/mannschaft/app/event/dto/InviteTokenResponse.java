package com.mannschaft.app.event.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 招待トークンレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class InviteTokenResponse {

    private final Long id;
    private final Long eventId;
    private final String token;
    private final String label;
    private final Integer maxUses;
    private final Integer usedCount;
    private final LocalDateTime expiresAt;
    private final Boolean isActive;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
