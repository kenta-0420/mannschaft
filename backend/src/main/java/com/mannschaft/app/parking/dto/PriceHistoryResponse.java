package com.mannschaft.app.parking.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 区画料金履歴レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PriceHistoryResponse {

    private final Long id;
    private final Long spaceId;
    private final BigDecimal oldPrice;
    private final BigDecimal newPrice;
    private final Long changedBy;
    private final LocalDateTime changedAt;
}
