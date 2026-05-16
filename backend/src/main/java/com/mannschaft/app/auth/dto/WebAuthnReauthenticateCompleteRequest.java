package com.mannschaft.app.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * WebAuthn 再認証完了リクエスト DTO。
 *
 * <p>F18 提示モード追加保護（設計書 §9.6）で使用する。
 * 既存の {@link WebAuthnLoginCompleteRequest} と異なり、{@code signCount} を必須にせず、
 * {@code AuthenticationParameters} の検証で increment を強制する設計とする。
 *
 * <p>※ AT/RT を発行しない再認証専用なので、本リクエストでのトークン rotation は行わない。
 */
@Getter
@RequiredArgsConstructor
public class WebAuthnReauthenticateCompleteRequest {

    @NotBlank
    private final String credentialId;

    @NotBlank
    private final String authenticatorData;

    @NotBlank
    private final String clientDataJson;

    @NotBlank
    private final String signature;

    /**
     * クライアントが計測した sign_count。リプレイ防止のため
     * Service 層で {@code <= 保存値} の場合は AUTH_026 を投げる。
     */
    private final long signCount;
}
