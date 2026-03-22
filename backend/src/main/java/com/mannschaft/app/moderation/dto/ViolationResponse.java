package com.mannschaft.app.moderation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ユーザー違反レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ViolationResponse {

    private final Long id;
    private final Long userId;
    private final Long reportId;
    private final Long actionId;
    private final String violationType;
    private final String reason;
    private final LocalDateTime expiresAt;
    private final Boolean isActive;
    private final LocalDateTime createdAt;
}
