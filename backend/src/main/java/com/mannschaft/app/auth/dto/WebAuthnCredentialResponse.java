package com.mannschaft.app.auth.dto;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * WebAuthnクレデンシャル情報レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class WebAuthnCredentialResponse {

    private final Long id;
    private final String credentialId;
    private final String deviceName;
    private final String aaguid;
    private final LocalDateTime lastUsedAt;
    private final LocalDateTime createdAt;
}
