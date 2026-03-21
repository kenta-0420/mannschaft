package com.mannschaft.app.activity.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 活動統計レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
@Builder
public class ActivityStatsResponse {

    private final long totalActivities;
    private final List<TemplateCount> byTemplate;
    private final List<MonthCount> byMonth;
    private final List<TopParticipant> topParticipants;

    @Getter
    @RequiredArgsConstructor
    public static class TemplateCount {
        private final Long templateId;
        private final String templateName;
        private final long count;
    }

    @Getter
    @RequiredArgsConstructor
    public static class MonthCount {
        private final String month;
        private final long count;
    }

    @Getter
    @RequiredArgsConstructor
    public static class TopParticipant {
        private final Long userId;
        private final String displayName;
        private final long participationCount;
    }
}
