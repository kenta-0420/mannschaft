package com.mannschaft.app.schedule.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * スケジュール更新リクエストDTO。部分更新に対応する。
 *
 * <p>機能55 BE対応: reminders / scheduledSurveys / scheduledAttendance を追加。
 * null = 変更なし（部分更新セマンティクス）。空リスト = 全削除。</p>
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

    /**
     * 共有リマインダー一覧（機能55 BE対応）。
     *
     * <p>null = 変更なし（既存リマインダーを保持）。
     * 空リスト = 既存リマインダーを全削除。
     * 非空リスト = 既存を全削除して新規登録（差し替え）。
     * 最大5件。</p>
     */
    @Size(max = 5)
    @Valid
    private final List<CreateReminderRequest> reminders;

    /**
     * 予約アンケート一覧（機能55 BE対応）。
     *
     * <p>null = 変更なし。空リスト = PENDING のアンケートタスクを全削除。
     * 非空リスト = PENDING タスクを全 CANCEL して新規登録。
     * 最大10件。</p>
     */
    @Size(max = 10)
    @Valid
    private final List<ScheduledSurveyRequest> scheduledSurveys;

    /**
     * 予約出欠募集（機能55 BE対応）。
     *
     * <p>null = 変更なし。非null = PENDING の ATTENDANCE タスクを CANCEL して新規登録。</p>
     */
    @Valid
    private final ScheduledAttendanceRequest scheduledAttendance;
}
