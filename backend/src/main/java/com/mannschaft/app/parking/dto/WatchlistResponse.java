package com.mannschaft.app.parking.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ウォッチリストレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class WatchlistResponse {

    private final Long id;
    private final Long userId;
    private final String scopeType;
    private final Long scopeId;
    private final String spaceType;
    private final String floor;
    private final BigDecimal maxPrice;
    private final Boolean isActive;
    private final LocalDateTime createdAt;
}
