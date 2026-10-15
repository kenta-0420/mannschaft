package com.mannschaft.app.auth.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * WebAuthn登録開始時のチャレンジレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class WebAuthnRegisterBeginResponse {

    private final String challenge;
    private final String rpId;
    private final String rpName;
    private final Long userId;
    private final String userDisplayName;
}
