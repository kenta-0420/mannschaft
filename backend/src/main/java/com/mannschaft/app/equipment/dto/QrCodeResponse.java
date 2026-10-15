package com.mannschaft.app.equipment.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * QRコードレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class QrCodeResponse {

    private final Long id;
    private final String name;
    private final String category;
    private final String storageLocation;
    private final String qrCode;
    private final String qrUrl;
}
