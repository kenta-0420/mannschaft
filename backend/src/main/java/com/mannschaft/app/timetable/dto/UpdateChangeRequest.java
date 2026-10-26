package com.mannschaft.app.timetable.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class UpdateChangeRequest {
    private final String subjectName;
    private final String teacherName;
    private final String roomName;
    private final String reason;
    private final Boolean notifyMembers;
}
