package com.mannschaft.app.auth.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.auth.MfaVerificationMethod;
import lombok.Getter;

/**
 * バックアップコード再生成リクエスト（{@code POST /api/v1/auth/2fa/backup-codes/regenerate}。
 * CMP-261012-0910 AC-44）。
 *
 * <p>本人確認として「現在のパスワード」または「有効な要素（TOTP / 本人確認用メールOTP / バックアップコード）」
 * のいずれかを要求する。</p>
 */
@Getter
public class RegenerateBackupCodesRequest {

    private final String currentPassword;
    private final MfaVerificationMethod method;
    private final String code;

    @JsonCreator
    public RegenerateBackupCodesRequest(@JsonProperty("currentPassword") String currentPassword,
                                        @JsonProperty("method") MfaVerificationMethod method,
                                        @JsonProperty("code") String code) {
        this.currentPassword = currentPassword;
        this.method = method;
        this.code = code;
    }
}
