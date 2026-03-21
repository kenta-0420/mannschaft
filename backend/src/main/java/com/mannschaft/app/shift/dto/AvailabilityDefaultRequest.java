package com.mannschaft.app.shift.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalTime;

/**
 * デフォルト勤務可能時間設定リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class AvailabilityDefaultRequest {

    @NotNull
    private final Integer dayOfWeek;

    @NotNull
    private final LocalTime startTime;

    @NotNull
    private final LocalTime endTime;

    @NotNull
    private final String preference;

    @Size(max = 200)
    private final String note;
}
