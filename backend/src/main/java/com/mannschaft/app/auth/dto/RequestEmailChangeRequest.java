package com.mannschaft.app.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * メールアドレス変更リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class RequestEmailChangeRequest {

    @NotBlank
    @Email
    private final String newEmail;

    @NotBlank
    private final String currentPassword;
}
