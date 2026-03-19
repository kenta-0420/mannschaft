package com.mannschaft.app.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ログインリクエスト。
 */
@Getter
@RequiredArgsConstructor
public class LoginRequest {

    @NotBlank
    @Email
    private final String email;

    @NotBlank
    private final String password;

    private final boolean rememberMe;
    private final String deviceFingerprint;
}
