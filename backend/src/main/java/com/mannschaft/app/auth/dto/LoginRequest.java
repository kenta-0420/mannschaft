package com.mannschaft.app.auth.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

/**
 * ログインリクエスト。
 */
@Getter
public class LoginRequest {

    @NotBlank
    @Email
    private final String email;

    @NotBlank
    private final String password;

    private final boolean rememberMe;
    private final String deviceFingerprint;

    @JsonCreator
    public LoginRequest(
            @JsonProperty("email") String email,
            @JsonProperty("password") String password,
            @JsonProperty("rememberMe") boolean rememberMe,
            @JsonProperty("deviceFingerprint") String deviceFingerprint) {
        this.email = email;
        this.password = password;
        this.rememberMe = rememberMe;
        this.deviceFingerprint = deviceFingerprint;
    }
}
