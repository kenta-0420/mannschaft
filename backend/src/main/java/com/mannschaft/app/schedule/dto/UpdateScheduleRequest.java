package com.mannschaft.app.schedule.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.OffsetDateTime;
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

    /**
     * 開始日時。クライアントTZ付きで受け取り、JST に変換して保存する（null = 変更なし）。
     */
    private final OffsetDateTime startAt;

    /**
     * 終了日時。クライアントTZ付きで受け取り、JST に変換して保存する（null = 変更なし）。
     */
    private final OffsetDateTime endAt;

    private final Boolean allDay;

    private final String eventType;

    private final String visibility;

    private final String minViewRole;

    private final String minResponseRole;

    /** null なら対象モードを更新しない。 */
    private final String targetMode;

    /** null なら対象者を更新しない。SELECTED_MEMBERS 時は1〜500件。 */
    @Size(max = 500)
    private final List<Long> targetUserIds;

    private final Boolean attendanceRequired;

    /**
     * 出欠締切日時。クライアントTZ付きで受け取り、JST に変換して保存する（null = 変更なし）。
     */
    private final OffsetDateTime attendanceDeadline;

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
     * 最大5件。編集コンテキストのため {@link UpdateReminderRequest} を使用し、
     * 既存の絶対リマインダーが過去日時でも保存できる。</p>
     */
    @Size(max = 5)
    @Valid
    private final List<UpdateReminderRequest> reminders;

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

    /** CMP-057以前のJava呼び出し元向け。対象者項目は未変更（null）として扱う。 */
    public UpdateScheduleRequest(
            String title, String description, String location,
            OffsetDateTime startAt, OffsetDateTime endAt, Boolean allDay,
            String eventType, String visibility, String minViewRole, String minResponseRole,
            Boolean attendanceRequired, OffsetDateTime attendanceDeadline, String commentOption,
            Long eventCategoryId, Integer academicYear, String updateScope,
            List<UpdateReminderRequest> reminders,
            List<ScheduledSurveyRequest> scheduledSurveys,
            ScheduledAttendanceRequest scheduledAttendance) {
        this(title, description, location, startAt, endAt, allDay, eventType, visibility,
                minViewRole, minResponseRole, null, null, attendanceRequired, attendanceDeadline,
                commentOption, eventCategoryId, academicYear, updateScope, reminders,
                scheduledSurveys, scheduledAttendance);
    }
}
