package com.mannschaft.app.auth.dto;

import java.time.LocalDateTime;

import lombok.Getter;

/**
 * ログイン成功時のレスポンス。退会申請中のユーザーの復帰情報を含む。
 */
@Getter
public class LoginResponse extends TokenResponse {

    private final Long userId;
    private final String displayName;
    private final String email;
    private final LocalDateTime pendingDeletionUntil;
    private final boolean reactivated;

    public LoginResponse(String accessToken, String refreshToken, long expiresIn,
                         Long userId, String displayName, String email,
                         LocalDateTime pendingDeletionUntil, boolean reactivated) {
        super(accessToken, refreshToken, expiresIn);
        this.userId = userId;
        this.displayName = displayName;
        this.email = email;
        this.pendingDeletionUntil = pendingDeletionUntil;
        this.reactivated = reactivated;
    }
}
