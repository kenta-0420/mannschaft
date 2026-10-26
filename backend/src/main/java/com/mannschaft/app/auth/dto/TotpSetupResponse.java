package com.mannschaft.app.auth.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * TOTPセットアップ時のシークレットとQRコードURLレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class TotpSetupResponse {

    private final String secret;
    private final String qrCodeUrl;
}
