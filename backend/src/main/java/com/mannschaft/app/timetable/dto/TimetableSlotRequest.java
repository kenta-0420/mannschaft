package com.mannschaft.app.timetable.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class TimetableSlotRequest {
    @NotBlank
    private final String dayOfWeek;
    @NotNull
    private final Integer periodNumber;
    private final String weekPattern;  // EVERY, A, B
    @Size(max = 100)
    private final String subjectName;
    @Size(max = 100)
    private final String teacherName;
    @Size(max = 100)
    private final String roomName;
    @Size(max = 7)
    private final String color;
    private final String notes;
}
