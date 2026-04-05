package com.mannschaft.app.auth.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * OAuthアカウント競合時のレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class OAuthConflictResponse {

    private final boolean oauthConflict;
    private final String message;
    private final String email;
    private final String provider;

    public OAuthConflictResponse(String message, String email, String provider) {
        this.oauthConflict = true;
        this.message = message;
        this.email = email;
        this.provider = provider;
    }
}
