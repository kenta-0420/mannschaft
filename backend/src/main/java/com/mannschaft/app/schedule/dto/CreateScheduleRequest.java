package com.mannschaft.app.schedule.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * スケジュール作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateScheduleRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    @Size(max = 5000)
    private final String description;

    @Size(max = 300)
    private final String location;

    @NotNull
    private final LocalDateTime startAt;

    private final LocalDateTime endAt;

    @NotNull
    private final Boolean allDay;

    @NotNull
    private final String eventType;

    private final String visibility;

    private final String minViewRole;

    private final String minResponseRole;

    @NotNull
    private final Boolean attendanceRequired;

    private final LocalDateTime attendanceDeadline;

    private final String commentOption;

    /** 行事カテゴリID（任意。F03.10 拡張フィールド）。 */
    private final Long eventCategoryId;

    /** 年度（任意。F03.10 拡張フィールド。例: 2026）。 */
    private final Integer academicYear;

    private final RecurrenceRuleDto recurrenceRule;

    @Size(max = 10)
    private final List<CreateSurveyRequest> surveys;

    @Size(max = 5)
    private final List<CreateReminderRequest> reminders;

    // --- 機能55 第二陣: 予約作成（予約アンケート / 予約出欠募集） ---

    /**
     * 予約アンケート（任意・最大10件）。指定時刻に集計可能な本格アンケートを自動生成・公開する。
     * materialize は後続バッチ（{@code ScheduleScheduledTaskBatchService}）が担う。
     */
    @Size(max = 10)
    @Valid
    private final List<ScheduledSurveyRequest> scheduledSurveys;

    /**
     * 予約出欠募集（任意・単一）。指定時刻に出欠レコードを生成し対象メンバーへ募集通知を配信する。
     * materialize は後続バッチ（{@code ScheduleScheduledTaskBatchService}）が担う。
     */
    @Valid
    private final ScheduledAttendanceRequest scheduledAttendance;
}
