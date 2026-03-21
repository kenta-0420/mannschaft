package com.mannschaft.app.safetycheck.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * 安否確認回答リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class RespondRequest {

    @NotNull
    private final String status;

    @Size(max = 200)
    private final String message;

    private final String messageSource;

    private final Boolean gpsShared;

    private final BigDecimal gpsLatitude;

    private final BigDecimal gpsLongitude;
}
