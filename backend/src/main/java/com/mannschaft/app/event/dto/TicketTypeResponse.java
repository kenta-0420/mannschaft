package com.mannschaft.app.event.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * チケット種別レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TicketTypeResponse {

    private final Long id;
    private final Long eventId;
    private final String name;
    private final String description;
    private final BigDecimal price;
    private final String currency;
    private final Integer maxQuantity;
    private final Integer issuedCount;
    private final String minRegistrationRole;
    private final Boolean isActive;
    private final Integer sortOrder;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
