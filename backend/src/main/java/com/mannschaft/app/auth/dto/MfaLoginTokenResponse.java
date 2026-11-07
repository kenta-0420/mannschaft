package com.mannschaft.app.auth.dto;

import com.mannschaft.app.auth.MfaVerificationMethod;
import lombok.Getter;

/**
 * 2FA ログイン検証（{@code POST /api/v1/auth/2fa/validate}）成功時のレスポンス（CMP-261012-0910）。
 *
 * <p>{@link TokenResponse} に、使った要素（{@link #method}）と、バックアップコード使用時の
 * 残り件数（{@link #backupCodesRemaining}。AC-12。BACKUP_CODE 以外では null）を加える。</p>
 */
@Getter
public class MfaLoginTokenResponse extends TokenResponse {

    private final MfaVerificationMethod method;
    private final Integer backupCodesRemaining;

    public MfaLoginTokenResponse(TokenResponse tokens, MfaVerificationMethod method, Integer backupCodesRemaining) {
        super(tokens.getAccessToken(), tokens.getRefreshToken(), tokens.getSessionId(), tokens.getExpiresIn());
        this.method = method;
        this.backupCodesRemaining = backupCodesRemaining;
    }
}
