package com.mannschaft.app.auth.dto;

import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * WebAuthnログイン開始時のチャレンジレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class WebAuthnLoginBeginResponse {

    private final String challenge;
    private final String rpId;
    private final List<String> allowCredentials;
    private final long timeout;
}
