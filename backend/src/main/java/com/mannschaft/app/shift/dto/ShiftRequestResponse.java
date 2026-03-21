package com.mannschaft.app.shift.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * シフト希望レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ShiftRequestResponse {

    private final Long id;
    private final Long scheduleId;
    private final Long userId;
    private final Long slotId;
    private final LocalDate slotDate;
    private final String preference;
    private final String note;
    private final LocalDateTime submittedAt;
}
