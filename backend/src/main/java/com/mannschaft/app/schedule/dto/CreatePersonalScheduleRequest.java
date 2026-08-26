package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 個人スケジュール作成リクエストDTO。
 *
 * <p>機能55 第二陣でリマインダーの絶対日時指定に対応。既存の {@link #reminders}（相対・開始N分前）は
 * 後方互換のため残し、絶対日時指定として {@link #absoluteReminders} を追加する。
 * 相対・絶対の合算件数は最大 5 件（{@link #isReminderCountWithinLimit()} で検証）。</p>
 */
@Getter
@RequiredArgsConstructor
public class CreatePersonalScheduleRequest {

    /** 相対・絶対を合算したリマインダーの上限件数。 */
    public static final int MAX_TOTAL_REMINDERS = 5;

    @NotBlank
    @Size(max = 200)
    private final String title;

    @Size(max = 5000)
    private final String description;

    @Size(max = 300)
    private final String location;

    /**
     * 開始日時。クライアントTZ付きで受け取り、JST に変換して保存する（例: 2026-06-04T10:00:00+09:00）。
     */
    @NotNull
    private final OffsetDateTime startAt;

    /**
     * 終了日時。クライアントTZ付きで受け取り、JST に変換して保存する。
     */
    private final OffsetDateTime endAt;

    @NotNull
    private final Boolean allDay;

    private final String eventType;

    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$")
    private final String color;

    /** 相対指定リマインダー（開始N分前）。既存仕様を踏襲し単体では最大3件。 */
    @Size(max = 3)
    private final List<Integer> reminders;

    /**
     * 絶対指定リマインダー（固定日時）。任意・単体では最大3件。
     * OffsetDateTime で受け取ることでクライアントのタイムゾーンを正確に把握する。
     * FE はユーザーのローカルタイムゾーンのオフセットを付与して送信すること（例: 2026-06-04T08:00:00+09:00）。
     * BE は PersonalScheduleService.saveReminders() で JVM TZ（Asia/Tokyo）へ変換して保存する。
     * @Future 制約は OffsetDateTime に不要（過去日時も保存可能とし、バッチ側で未送信判定する）。
     */
    @Size(max = 3)
    private final List<OffsetDateTime> absoluteReminders;

    private final RecurrenceRuleDto recurrenceRule;

    /**
     * eventType のデフォルト値を返す。null の場合は OTHER を返す。
     */
    public String getEventTypeOrDefault() {
        return eventType != null ? eventType : "OTHER";
    }

    /**
     * 相対・絶対を合算したリマインダー件数が上限以内かを検証する。
     *
     * @return 合算件数が {@link #MAX_TOTAL_REMINDERS} 以下なら true
     */
    @AssertTrue(message = "リマインダーは相対・絶対の合計で最大5件です")
    public boolean isReminderCountWithinLimit() {
        int relative = reminders != null ? reminders.size() : 0;
        int absolute = absoluteReminders != null ? absoluteReminders.size() : 0;
        return relative + absolute <= MAX_TOTAL_REMINDERS;
    }
}
