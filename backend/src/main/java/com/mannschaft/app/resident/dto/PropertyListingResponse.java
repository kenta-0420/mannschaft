package com.mannschaft.app.resident.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 物件掲示レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PropertyListingResponse {

    private final Long id;
    private final Long dwellingUnitId;
    private final Long listedBy;
    private final String listingType;
    private final String title;
    private final String description;
    private final BigDecimal askingPrice;
    private final BigDecimal monthlyRent;
    private final String status;
    private final LocalDateTime expiresAt;
    private final String imageUrls;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
