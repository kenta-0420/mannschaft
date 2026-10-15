package com.mannschaft.app.resident.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 物件問い合わせレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class InquiryResponse {

    private final Long id;
    private final Long listingId;
    private final Long userId;
    private final String message;
    private final LocalDateTime createdAt;
}
