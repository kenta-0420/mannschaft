package com.mannschaft.app.resident.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 物件掲示更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdatePropertyListingRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    private final String description;

    private final BigDecimal askingPrice;

    private final BigDecimal monthlyRent;

    private final LocalDateTime expiresAt;

    private final List<String> imageUrls;
}
