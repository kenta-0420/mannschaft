package com.mannschaft.app.parking.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 定期来場者予約テンプレートレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class VisitorRecurringResponse {

    private final Long id;
    private final Long userId;
    private final Long spaceId;
    private final String recurrenceType;
    private final Integer dayOfWeek;
    private final Integer dayOfMonth;
    private final LocalTime timeFrom;
    private final LocalTime timeTo;
    private final String visitorName;
    private final String visitorPlateNumber;
    private final String purpose;
    private final Boolean isActive;
    private final LocalDate nextGenerateDate;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
