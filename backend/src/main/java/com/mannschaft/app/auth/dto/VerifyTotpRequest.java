package com.mannschaft.app.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * TOTP検証リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class VerifyTotpRequest {

    @NotBlank
    @Size(min = 6, max = 6)
    private final String totpCode;
}
