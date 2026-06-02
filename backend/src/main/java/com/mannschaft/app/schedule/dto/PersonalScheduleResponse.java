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

    /**
     * 相対指定リマインダー（開始 N 分前）の分数一覧。後方互換のため維持。
     * 絶対指定リマインダーはここには載らない（{@link #detailedReminders} を参照）。
     */
    List<Integer>      reminders;

    /**
     * リマインダー詳細一覧（機能55 第三陣）。相対（remindBeforeMinutes）と絶対（remindAt）の
     * <b>両方</b>を {@code reminderKind} 付きで露出する。詳細 GET のみ populate（一覧 GET では null）。
     * 足軽3 時点で相対のみ露出だった不足を是正するためのフィールド。
     */
    List<ReminderResponse> detailedReminders;

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
