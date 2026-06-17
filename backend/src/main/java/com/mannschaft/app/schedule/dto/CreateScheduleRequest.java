package com.mannschaft.app.schedule.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.OffsetDateTime;
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

    /**
     * 開始日時。クライアントのタイムゾーン情報付きで受け取る（例: 2026-06-04T10:00:00+09:00）。
     * BE は {@code OffsetDateTime.atZoneSameInstant(Asia/Tokyo).toLocalDateTime()} で JST に変換して保存する。
     */
    @NotNull
    private final OffsetDateTime startAt;

    /**
     * 終了日時。startAt と同様にクライアントTZ付きで受け取り、JST に変換して保存する。
     */
    private final OffsetDateTime endAt;

    @NotNull
    private final Boolean allDay;

    @NotNull
    private final String eventType;

    private final String visibility;

    private final String minViewRole;

    private final String minResponseRole;

    @NotNull
    private final Boolean attendanceRequired;

    /**
     * 出欠締切日時。クライアントTZ付きで受け取り、JST に変換して保存する。
     */
    private final OffsetDateTime attendanceDeadline;

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

    /**
     * 出欠確認の配信母集団にサポーター（応援者）を含めるか。省略時 false（組織配信時はサポーター除外）。
     * (B) 組織→参加チーム配信 案C フェーズA 隊A で追加。値を使った母集団絞り込みは後続隊。
     */
    private final Boolean includeSupporters;
}
