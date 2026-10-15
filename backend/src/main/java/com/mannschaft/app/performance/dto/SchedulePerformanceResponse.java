package com.mannschaft.app.performance.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * スケジュール紐付きパフォーマンス一覧レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SchedulePerformanceResponse {

    private final Long scheduleId;
    private final String scheduleName;
    private final LocalDate recordedDate;
    private final List<MemberRecords> members;

    @Getter
    @RequiredArgsConstructor
    public static class MemberRecords {
        private final Long userId;
        private final String displayName;
        private final List<RecordEntry> records;
    }

    @Getter
    @RequiredArgsConstructor
    public static class RecordEntry {
        private final Long metricId;
        private final String metricName;
        private final BigDecimal value;
        private final String unit;
    }
}
