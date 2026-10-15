package com.mannschaft.app.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * WebAuthn登録完了リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class WebAuthnRegisterCompleteRequest {

    @NotBlank
    private final String credentialId;

    @NotBlank
    private final String attestationObject;

    @NotBlank
    private final String clientDataJson;

    @NotBlank
    private final String publicKey;

    private final String deviceName;
    private final String aaguid;
}
