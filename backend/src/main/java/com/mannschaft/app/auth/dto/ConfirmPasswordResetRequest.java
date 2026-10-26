package com.mannschaft.app.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * パスワードリセット確認リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class ConfirmPasswordResetRequest {

    @NotBlank
    private final String token;

    @NotBlank
    @Size(min = 8)
    private final String newPassword;
}
