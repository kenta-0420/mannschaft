package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * スケジュール一覧用レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ScheduleResponse {

    private final Long id;
    private final String title;
    private final LocalDateTime startAt;
    private final LocalDateTime endAt;
    private final Boolean allDay;
    private final String eventType;
    private final String status;
    private final Boolean attendanceRequired;
    private final String location;
    private final LocalDateTime createdAt;

    /** 行事カテゴリ（F03.10 拡張フィールド。未設定の場合 null）。 */
    private final EventCategoryResponse eventCategory;

    /** 年度（F03.10 拡張フィールド。未設定の場合 null）。 */
    private final Integer academicYear;

    /** コピー元スケジュールID（F03.10 拡張フィールド。前年度トレース時のみ設定）。 */
    private final Long sourceScheduleId;

    private final String createdByDisplayName;
    private final String scopeName;
    private final String scopeIconUrl;
}
