package com.mannschaft.app.schedule.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 個人スケジュールレスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class PersonalScheduleResponse {

    Long               id;
    PersonalContentDto content;   // title, description, eventType, color, location
    PersonalTimeDto    time;      // startAt, endAt, allDay
    PersonalStatusDto  status;    // status, isException, parentScheduleId, recurrenceRule, googleSynced
    List<Integer>      reminders;
    PersonalAuditDto   audit;     // createdAt, updatedAt, createdByDisplayName

    public record PersonalContentDto(String title, String description, String eventType, String color,
                                     String location) {
    }

    public record PersonalTimeDto(LocalDateTime startAt, LocalDateTime endAt, Boolean allDay) {
    }

    public record PersonalStatusDto(String status, Boolean isException, Long parentScheduleId,
                                    RecurrenceRuleDto recurrenceRule, boolean googleSynced) {
    }

    public record PersonalAuditDto(LocalDateTime createdAt, LocalDateTime updatedAt,
                                   String createdByDisplayName) {
    }
}
