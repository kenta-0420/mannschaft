package com.mannschaft.app.timetable.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalTime;

@Getter
@RequiredArgsConstructor
public class PeriodTemplateResponse {
    private final Long id;
    private final Integer periodNumber;
    private final String label;
    private final LocalTime startTime;
    private final LocalTime endTime;
    private final Boolean isBreak;
}
