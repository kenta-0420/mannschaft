package com.mannschaft.app.auth.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

/**
 * ログイン時のメールOTP 送信リクエスト（{@code POST /api/v1/auth/2fa/email/send}。公開 EP）。
 *
 * <p>CMP-261012-0910 AC-18: MFA セッショントークンはクエリ文字列ではなく本文で受ける
 * （アクセスログ・Referer への漏えいを避けるため）。</p>
 */
@Getter
public class MfaEmailSendRequest {

    @NotBlank
    private final String mfaSessionToken;

    @JsonCreator
    public MfaEmailSendRequest(@JsonProperty("mfaSessionToken") String mfaSessionToken) {
        this.mfaSessionToken = mfaSessionToken;
    }
}
