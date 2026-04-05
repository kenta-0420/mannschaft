package com.mannschaft.app.timetable.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

@Getter
@RequiredArgsConstructor
public class CreateChangeRequest {
    @NotNull
    private final LocalDate targetDate;
    private final Integer periodNumber;
    @NotNull
    private final String changeType;  // REPLACE, CANCEL, ADD, DAY_OFF
    private final String subjectName;
    private final String teacherName;
    private final String roomName;
    private final String reason;
    private final Boolean notifyMembers;
    private final Boolean createSchedule;
}
