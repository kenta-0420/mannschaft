package com.mannschaft.app.shift.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalTime;

/**
 * デフォルト勤務可能時間レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class AvailabilityDefaultResponse {

    private final Long id;
    private final Long userId;
    private final Long teamId;
    private final Integer dayOfWeek;
    private final LocalTime startTime;
    private final LocalTime endTime;
    private final String preference;
    private final String note;
}
