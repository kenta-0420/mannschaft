package com.mannschaft.app.parking.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 定期来場者予約テンプレート作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateVisitorRecurringRequest {

    @NotNull
    private final Long spaceId;

    @NotNull
    private final String recurrenceType;

    private final Integer dayOfWeek;

    private final Integer dayOfMonth;

    @NotNull
    private final LocalTime timeFrom;

    @NotNull
    private final LocalTime timeTo;

    @Size(max = 100)
    private final String visitorName;

    @Size(max = 30)
    private final String visitorPlateNumber;

    @Size(max = 200)
    private final String purpose;

    @NotNull
    private final LocalDate nextGenerateDate;
}
