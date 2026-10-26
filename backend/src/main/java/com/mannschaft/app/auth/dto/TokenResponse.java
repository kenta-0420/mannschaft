package com.mannschaft.app.auth.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * アクセストークン・リフレッシュトークンのレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class TokenResponse {

    private final String accessToken;
    private final String refreshToken;
    /** セッションID（refresh_tokens.id）。モバイルが保持し GET /sessions 時に送信する（F12.4） */
    private final Long sessionId;
    private final String tokenType;
    private final long expiresIn;

    public TokenResponse(String accessToken, String refreshToken, long expiresIn) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.sessionId = null;
        this.tokenType = "Bearer";
        this.expiresIn = expiresIn;
    }

    public TokenResponse(String accessToken, String refreshToken, Long sessionId, long expiresIn) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.sessionId = sessionId;
        this.tokenType = "Bearer";
        this.expiresIn = expiresIn;
    }
}
