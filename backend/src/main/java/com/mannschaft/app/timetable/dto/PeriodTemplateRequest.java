package com.mannschaft.app.timetable.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalTime;

@Getter
@RequiredArgsConstructor
public class PeriodTemplateRequest {
    @NotNull
    private final Integer periodNumber;
    @Size(max = 50)
    private final String label;
    @NotNull
    private final LocalTime startTime;
    @NotNull
    private final LocalTime endTime;
    @NotNull
    private final Boolean isBreak;
}
