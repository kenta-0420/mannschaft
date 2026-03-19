package com.mannschaft.app.auth.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * MFA認証が必要な場合のレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class MfaRequiredResponse {

    private final boolean mfaRequired;
    private final String mfaSessionToken;

    public MfaRequiredResponse(String mfaSessionToken) {
        this.mfaRequired = true;
        this.mfaSessionToken = mfaSessionToken;
    }
}
