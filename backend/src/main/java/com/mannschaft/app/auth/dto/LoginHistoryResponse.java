package com.mannschaft.app.auth.dto;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ログイン履歴レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class LoginHistoryResponse {

    private final Long id;
    private final String eventType;
    private final String ipAddress;
    private final String userAgent;
    private final String method;
    private final LocalDateTime createdAt;
}
