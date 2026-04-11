package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * スケジュール更新リクエストDTO。部分更新に対応する。
 */
@Getter
@RequiredArgsConstructor
public class UpdateScheduleRequest {

    @Size(max = 200)
    private final String title;

    @Size(max = 5000)
    private final String description;

    @Size(max = 300)
    private final String location;

    private final LocalDateTime startAt;

    private final LocalDateTime endAt;

    private final Boolean allDay;

    private final String eventType;

    private final String visibility;

    private final String minViewRole;

    private final String minResponseRole;

    private final Boolean attendanceRequired;

    private final LocalDateTime attendanceDeadline;

    private final String commentOption;

    /** 行事カテゴリID（任意。null 指定で解除。F03.10 拡張フィールド）。 */
    private final Long eventCategoryId;

    /** 年度（任意。null 指定で解除。F03.10 拡張フィールド）。 */
    private final Integer academicYear;

    private final String updateScope;

    /** 行事カテゴリID（F03.10 年間行事計画用）。任意項目。 */
    private final Long eventCategoryId;

    /** 年度（F03.10 年間行事計画用）。例: 2025（2025年度）。任意項目。 */
    private final Integer academicYear;
}
