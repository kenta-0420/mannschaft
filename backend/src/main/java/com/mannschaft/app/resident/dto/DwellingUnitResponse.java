package com.mannschaft.app.resident.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 居室レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class DwellingUnitResponse {

    private final Long id;
    private final String scopeType;
    private final Long teamId;
    private final Long organizationId;
    private final String unitNumber;
    private final Short floor;
    private final BigDecimal areaSqm;
    private final String layout;
    private final String unitType;
    private final String notes;
    private final Short residentCount;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
