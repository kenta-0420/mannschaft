package com.mannschaft.app.auth.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

/**
 * メール 2FA 有効化リクエスト（{@code POST /api/v1/auth/2fa/email/enable}。CMP-261012-0910 AC-25）。
 */
@Getter
public class EnableEmailTwoFactorRequest {

    /** 設定用メールOTP（6 桁）。 */
    @NotBlank
    private final String code;

    @JsonCreator
    public EnableEmailTwoFactorRequest(@JsonProperty("code") String code) {
        this.code = code;
    }
}
