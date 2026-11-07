package com.mannschaft.app.auth.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.auth.MfaVerificationMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

/**
 * 2FA 無効化リクエスト（{@code POST /api/v1/auth/2fa/disable}。CMP-261012-0910 AC-30）。
 *
 * <p>パスワードを持つユーザー（hasPassword=true）は {@link #currentPassword} と有効な要素の両方が必須。
 * パスワード未設定（OAuth 専用等）のユーザーは要素のみ。要素は TOTP / 本人確認用メールOTP（EMAIL）/
 * バックアップコード。</p>
 */
@Getter
public class DisableTwoFactorRequest {

    private final String currentPassword;

    @NotNull
    private final MfaVerificationMethod method;

    @NotBlank
    private final String code;

    @JsonCreator
    public DisableTwoFactorRequest(@JsonProperty("currentPassword") String currentPassword,
                                   @JsonProperty("method") MfaVerificationMethod method,
                                   @JsonProperty("code") String code) {
        this.currentPassword = currentPassword;
        this.method = method;
        this.code = code;
    }
}
