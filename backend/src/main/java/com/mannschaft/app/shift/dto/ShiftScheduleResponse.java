package com.mannschaft.app.shift.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * シフトスケジュールレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ShiftScheduleResponse {

    private final Long id;
    private final Long teamId;
    private final String title;
    private final String periodType;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final String status;
    private final LocalDateTime requestDeadline;
    private final String note;
    private final Long createdBy;
    private final LocalDateTime publishedAt;
    private final Long publishedBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
