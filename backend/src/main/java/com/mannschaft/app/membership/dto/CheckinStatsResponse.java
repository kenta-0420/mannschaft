package com.mannschaft.app.membership.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * チェックイン統計レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CheckinStatsResponse {

    private final Period period;
    private final long totalCheckins;
    private final long uniqueMembers;
    private final double averagePerDay;
    private final Map<String, Long> checkinTypeBreakdown;
    private final List<DayOfWeekCount> byDayOfWeek;
    private final List<HourCount> byHour;
    private final List<TopMember> topMembers;
    private final List<LocationCount> byLocation;

    /**
     * 集計期間。
     */
    @Getter
    @RequiredArgsConstructor
    public static class Period {
        private final LocalDate from;
        private final LocalDate to;
    }

    /**
     * 曜日別件数。
     */
    @Getter
    @RequiredArgsConstructor
    public static class DayOfWeekCount {
        private final String day;
        private final long count;
    }

    /**
     * 時間帯別件数。
     */
    @Getter
    @RequiredArgsConstructor
    public static class HourCount {
        private final int hour;
        private final long count;
    }

    /**
     * 来店頻度トップメンバー。
     */
    @Getter
    @RequiredArgsConstructor
    public static class TopMember {
        private final String cardNumber;
        private final String displayName;
        private final long checkinCount;
    }

    /**
     * 場所別件数。
     */
    @Getter
    @RequiredArgsConstructor
    public static class LocationCount {
        private final String locationName;
        private final String checkinType;
        private final long count;
    }
}
