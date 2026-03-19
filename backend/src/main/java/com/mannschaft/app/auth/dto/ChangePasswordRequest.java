package com.mannschaft.app.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * パスワード変更リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class ChangePasswordRequest {

    @NotBlank
    private final String currentPassword;

    @NotBlank
    @Size(min = 8)
    private final String newPassword;
}
