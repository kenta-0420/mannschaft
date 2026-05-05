package com.mannschaft.app.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * パスワードリセット要求リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class RequestPasswordResetRequest {

    @NotBlank
    @Email
    private final String email;
}
