package com.mannschaft.app.auth.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;

/**
 * メール 2FA 有効化リクエスト（{@code POST /api/v1/auth/2fa/email/enable}。CMP-261012-0910 AC-25）。
 */
@Getter
public class EnableEmailTwoFactorRequest {

    /** 設定用メールOTP（6 桁）。形式違反は 400 で、AC-45 の試行回数に計上しない（AC-10 と同じ扱い）。 */
    @NotBlank
    @Pattern(regexp = "^\\d{6}$")
    private final String code;

    @JsonCreator
    public EnableEmailTwoFactorRequest(@JsonProperty("code") String code) {
        this.code = code;
    }
}
