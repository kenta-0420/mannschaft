package com.mannschaft.app.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * WebAuthnログイン完了リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class WebAuthnLoginCompleteRequest {

    @NotBlank
    private final String credentialId;

    @NotBlank
    private final String authenticatorData;

    @NotBlank
    private final String clientDataJson;

    @NotBlank
    private final String signature;

    private final long signCount;
}
