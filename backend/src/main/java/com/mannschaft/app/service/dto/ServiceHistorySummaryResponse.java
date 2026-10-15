package com.mannschaft.app.service.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/**
 * メンバー履歴サマリーレスポンス。
 */
@Getter
@Builder
public class ServiceHistorySummaryResponse {

    private Long memberUserId;
    private Long totalCount;
    private Long totalDurationMinutes;
    private Long averageDurationMinutes;
    private LocalDate lastServiceDate;
    private Long daysSinceLastService;
    private List<MonthlyCount> monthlyCounts;
    private List<TopStaff> topStaff;

    @Getter
    @Builder
    public static class MonthlyCount {
        private String month;
        private Long count;
        private Long durationMinutes;
    }

    @Getter
    @Builder
    public static class TopStaff {
        private Long staffUserId;
        private String staffName;
        private Long count;
    }
}
