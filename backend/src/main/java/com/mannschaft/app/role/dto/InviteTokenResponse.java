package com.mannschaft.app.role.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 招待トークンレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class InviteTokenResponse {

    private final Long id;
    private final String token;
    private final String roleName;
    private final LocalDateTime expiresAt;
    private final Integer maxUses;
    private final Integer usedCount;
    private final LocalDateTime revokedAt;
    private final LocalDateTime createdAt;
}
