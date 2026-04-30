package com.mannschaft.app.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * メール認証再送信リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class ResendVerificationRequest {

    @NotBlank
    @Email
    private final String email;
}
