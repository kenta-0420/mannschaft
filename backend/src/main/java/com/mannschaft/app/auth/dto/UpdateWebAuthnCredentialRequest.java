package com.mannschaft.app.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * WebAuthnクレデンシャルのデバイス名更新リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class UpdateWebAuthnCredentialRequest {

    @NotBlank
    @Size(max = 100)
    private final String deviceName;
}
