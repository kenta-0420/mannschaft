package com.mannschaft.app.queue.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * QRコードレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class QrCodeResponse {

    private final Long id;
    private final Long categoryId;
    private final Long counterId;
    private final String qrToken;
    private final Boolean isActive;
    private final LocalDateTime createdAt;
}
