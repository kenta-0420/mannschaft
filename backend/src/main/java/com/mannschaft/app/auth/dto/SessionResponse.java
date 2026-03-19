package com.mannschaft.app.auth.dto;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * アクティブセッション情報のレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class SessionResponse {

    private final Long id;
    private final String ipAddress;
    private final String userAgent;
    private final boolean rememberMe;
    private final LocalDateTime createdAt;
    private final LocalDateTime lastUsedAt;
    private final boolean isCurrent;
}
