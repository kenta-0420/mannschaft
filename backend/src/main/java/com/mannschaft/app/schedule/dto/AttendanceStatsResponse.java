package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 出欠統計レスポンスDTO。ユーザー別の出欠率を返す。
 */
@Getter
@RequiredArgsConstructor
public class AttendanceStatsResponse {

    private final Long userId;
    private final int totalSchedules;
    private final int attended;
    private final int absent;
    private final int partial;
    private final double attendanceRate;
}
