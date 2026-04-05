package com.mannschaft.app.reservation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalTime;

/**
 * 営業時間エントリDTO。1曜日分の営業時間設定。
 */
@Getter
@RequiredArgsConstructor
public class BusinessHourEntry {

    @NotBlank
    @Size(max = 3)
    private final String dayOfWeek;

    @NotNull
    private final Boolean isOpen;

    private final LocalTime openTime;

    private final LocalTime closeTime;
}
