package com.mannschaft.app.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * メール認証トークン検証リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class VerifyEmailRequest {

    @NotBlank
    private final String token;
}
