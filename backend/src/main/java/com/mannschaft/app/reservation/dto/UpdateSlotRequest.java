package com.mannschaft.app.reservation.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 予約スロット更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateSlotRequest {

    private final Long staffUserId;

    @Size(max = 200)
    private final String title;

    private final LocalDate slotDate;

    private final LocalTime startTime;

    private final LocalTime endTime;

    private final BigDecimal price;

    @Size(max = 2000)
    private final String note;
}
