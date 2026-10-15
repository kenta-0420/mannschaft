package com.mannschaft.app.ticket.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * QR コードレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class QrCodeResponse {

    private final String qrPayload;
    private final LocalDateTime expiresAt;
}
