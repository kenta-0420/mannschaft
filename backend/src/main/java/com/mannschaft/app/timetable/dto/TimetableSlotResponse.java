package com.mannschaft.app.timetable.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class TimetableSlotResponse {
    private final Long id;
    private final String dayOfWeek;
    private final Integer periodNumber;
    private final String weekPattern;
    private final String subjectName;
    private final String teacherName;
    private final String roomName;
    private final String color;
    private final String notes;
}
