package com.mannschaft.app.parking.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * サブリース詳細レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SubleaseDetailResponse {

    private final Long id;
    private final Long spaceId;
    private final Long assignmentId;
    private final Long offeredBy;
    private final String title;
    private final String description;
    private final BigDecimal pricePerMonth;
    private final String paymentMethod;
    private final LocalDate availableFrom;
    private final LocalDate availableTo;
    private final String status;
    private final Long matchedApplicationId;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
