package com.mannschaft.app.reservation.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;

import java.time.LocalTime;

/**
 * 営業時間エントリDTO。1曜日分の営業時間設定。
 */
@Getter
public class BusinessHourEntry {

    public BusinessHourEntry(String dayOfWeek, Boolean isOpen, LocalTime openTime, LocalTime closeTime) {
        this(dayOfWeek, isOpen, openTime, closeTime, false);
    }

    @JsonCreator
    public BusinessHourEntry(String dayOfWeek, Boolean isOpen, LocalTime openTime, LocalTime closeTime,
                             Boolean endsNextDay) {
        this.dayOfWeek = dayOfWeek;
        this.isOpen = isOpen;
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.endsNextDay = endsNextDay;
    }

    @NotBlank
    @Size(max = 3)
    private final String dayOfWeek;

    @NotNull
    private final Boolean isOpen;

    private final LocalTime openTime;

    private final LocalTime closeTime;

    /** closeTime が翌日になる業務時間か。省略時は従来互換の false。 */
    private final Boolean endsNextDay;
}
