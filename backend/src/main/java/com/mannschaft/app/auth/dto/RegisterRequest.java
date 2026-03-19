package com.mannschaft.app.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ユーザー新規登録リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class RegisterRequest {

    @NotBlank
    @Email
    private final String email;

    @NotBlank
    @Size(min = 8)
    private final String password;

    @NotBlank
    @Size(max = 50)
    private final String lastName;

    @NotBlank
    @Size(max = 50)
    private final String firstName;

    @NotBlank
    @Size(max = 50)
    private final String displayName;

    private final String locale;
    private final String timezone;
}
