package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 年間行事ビューレスポンスDTO。年度ベースの月別スケジュール一覧を返す。
 */
@Getter
@RequiredArgsConstructor
public class AnnualEventViewResponse {

    private final Integer academicYear;

    private final LocalDate yearStart;

    private final LocalDate yearEnd;

    private final List<EventCategoryResponse> categories;

    private final List<MonthEvents> months;

    private final Integer totalEvents;

    /**
     * 月別イベントデータ。
     */
    @Getter
    @RequiredArgsConstructor
    public static class MonthEvents {

        /** 月（例: "2026-04"） */
        private final String month;

        private final List<AnnualEventItem> events;
    }

    /**
     * 年間行事アイテム。
     */
    @Getter
    @RequiredArgsConstructor
    public static class AnnualEventItem {

        private final Long id;

        private final String title;

        private final LocalDateTime startAt;

        private final LocalDateTime endAt;

        private final Boolean allDay;

        private final String eventType;

        private final EventCategoryResponse eventCategory;

        private final String status;

        private final Long sourceScheduleId;
    }
}
