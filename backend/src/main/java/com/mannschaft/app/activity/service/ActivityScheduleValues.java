package com.mannschaft.app.activity.service;

import com.mannschaft.app.schedule.dto.ScheduleActivitySource;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 予定由来の同期対象5項目。全日の終了は予定の排他的終了日から記録の暦日に変換する。 */
public record ActivityScheduleValues(String title, LocalDate activityDate, LocalDate activityEndDate,
                                     LocalTime activityTimeStart, LocalTime activityTimeEnd) {
    public static ActivityScheduleValues from(ScheduleActivitySource source) {
        boolean allDay = Boolean.TRUE.equals(source.allDay());
        var startAt = source.startAt().atZoneSameInstant(
                com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser.SERVER_ZONE);
        var endAt = source.endAt() == null ? null : source.endAt().atZoneSameInstant(
                com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser.SERVER_ZONE);
        LocalDate start = startAt.toLocalDate();
        LocalDate end = endAt == null ? start : endAt.toLocalDate();
        if (allDay && endAt != null && endAt.toLocalTime().equals(LocalTime.MIDNIGHT)
                && end.isAfter(start)) end = end.minusDays(1);
        return new ActivityScheduleValues(source.title(), start, end.equals(start) ? null : end,
                allDay ? null : startAt.toLocalTime(),
                allDay || endAt == null ? null : endAt.toLocalTime());
    }

    public Map<String, Object> fields() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("title", title);
        values.put("activityDate", activityDate);
        values.put("activityEndDate", activityEndDate);
        values.put("activityTimeStart", activityTimeStart);
        values.put("activityTimeEnd", activityTimeEnd);
        return values;
    }
}
