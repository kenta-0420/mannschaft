package com.mannschaft.app.advertising.dto;

import com.mannschaft.app.advertising.CreditLimitRequestStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CreditLimitRequestDetailResponse(
    Long id,
    Long advertiserAccountId,
    String companyName,
    BigDecimal currentLimit,
    BigDecimal requestedLimit,
    String reason,
    CreditLimitRequestStatus status,
    LocalDateTime reviewedAt,
    String reviewNote,
    LocalDateTime createdAt
) {}
