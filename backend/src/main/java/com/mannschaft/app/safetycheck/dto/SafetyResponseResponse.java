package com.mannschaft.app.safetycheck.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 安否確認回答レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SafetyResponseResponse {

    private final Long id;
    private final Long safetyCheckId;
    private final Long userId;
    private final String status;
    private final String message;
    private final String messageSource;
    private final Boolean gpsShared;
    private final BigDecimal gpsLatitude;
    private final BigDecimal gpsLongitude;
    private final LocalDateTime respondedAt;
}
