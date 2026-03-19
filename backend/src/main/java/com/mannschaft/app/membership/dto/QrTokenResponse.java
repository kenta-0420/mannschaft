package com.mannschaft.app.membership.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * QRトークンレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class QrTokenResponse {

    private final Long memberCardId;
    private final String qrToken;
    private final int expiresIn;
    private final String cardNumber;
    private final String displayName;
    private final String scopeType;
    private final String scopeName;
}
