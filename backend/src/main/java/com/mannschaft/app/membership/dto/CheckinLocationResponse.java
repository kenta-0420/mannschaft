package com.mannschaft.app.membership.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * チェックイン拠点レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CheckinLocationResponse {

    private final Long id;
    private final String name;
    private final String locationCode;
    private final boolean isActive;
    private final boolean autoCompleteReservation;
    private final long checkinCountToday;
    private final LocalDateTime createdAt;
}
