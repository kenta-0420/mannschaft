package com.mannschaft.app.schedule.dto;

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

    /** 行事カテゴリID（F03.10 年間行事計画用）。任意項目。 */
    private final Long eventCategoryId;

    /** 年度（F03.10 年間行事計画用）。例: 2025（2025年度）。任意項目。 */
    private final Integer academicYear;
}
