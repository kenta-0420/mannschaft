package com.mannschaft.app.reservation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 予約スロット作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateSlotRequest {

    private final Long staffUserId;

    @Size(max = 200)
    private final String title;

    @NotNull
    private final LocalDate slotDate;

    @NotNull
    private final LocalTime startTime;

    @NotNull
    private final LocalTime endTime;

    private final String recurrenceRule;

    private final BigDecimal price;

    @Size(max = 2000)
    private final String note;
}
