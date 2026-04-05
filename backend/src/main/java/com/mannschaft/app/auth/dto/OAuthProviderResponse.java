package com.mannschaft.app.auth.dto;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * OAuth連携プロバイダー情報レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class OAuthProviderResponse {

    private final String provider;
    private final String providerEmail;
    private final LocalDateTime connectedAt;
}
