package com.mannschaft.app.membership.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 拠点QRコード取得レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class LocationQrResponse {

    private final Long locationId;
    private final String name;
    private final String qrToken;
    private final String scopeName;
    private final String printInstructions;
}
