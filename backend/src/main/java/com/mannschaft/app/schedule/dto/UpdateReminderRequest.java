package com.mannschaft.app.schedule.dto;

import com.mannschaft.app.schedule.ReminderKind;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.OffsetDateTime;

/**
 * リマインダー更新リクエストDTO（機能55 編集対応）。
 *
 * <p>{@link CreateReminderRequest} と構造は同じだが、絶対指定時の未来日時制約を持たない。
 * スケジュール編集時は既存リマインダーが過去日時になっている場合があるため、
 * 更新コンテキストでは過去日時も許容する。</p>
 *
 * <p>{@link #reminderKind} が {@link ReminderKind#ABSOLUTE}（既定）の場合は
 * {@link #remindAt}（絶対日時）を必須とし、
 * {@link ReminderKind#RELATIVE} の場合は {@link #remindBeforeMinutes}（開始N分前）を使用する。</p>
 *
 * <p>{@link #remindAt} は {@link OffsetDateTime} で受け取り、タイムゾーン情報を保持する。
 * BE はサービス層で JVM TZ（Asia/Tokyo）へ変換してから LocalDateTime として保存する。</p>
 */
@Getter
@RequiredArgsConstructor
public class UpdateReminderRequest {

    /**
     * 絶対日時（{@link ReminderKind#ABSOLUTE} 時に必須）。
     * OffsetDateTime で受け取ることでクライアントのタイムゾーンを正確に把握する。
     * 編集コンテキストでは過去日時も許容する（未来日時制約なし）。
     */
    private final OffsetDateTime remindAt;

    /** 相対指定：開始N分前（{@link ReminderKind#RELATIVE} 時に必須・正の整数）。 */
    @Positive
    private final Integer remindBeforeMinutes;

    /** リマインダー指定方式。null の場合は後方互換のため ABSOLUTE とみなす。 */
    private final ReminderKind reminderKind;

    /**
     * 有効な {@link ReminderKind} を返す。null の場合は ABSOLUTE。
     */
    public ReminderKind effectiveKind() {
        return reminderKind != null ? reminderKind : ReminderKind.ABSOLUTE;
    }

    /**
     * ABSOLUTE の場合のみ {@link #remindAt} の必須チェックを行う。
     * 編集コンテキストでは過去日時を許容するため、未来日時チェックは行わない。
     *
     * @return ABSOLUTE で remindAt が非null、または RELATIVE なら true
     */
    @AssertTrue(message = "絶対指定リマインダーには日時が必要です")
    public boolean isRemindAtValid() {
        if (effectiveKind() != ReminderKind.ABSOLUTE) {
            // RELATIVE 時は remindAt を使わない
            return true;
        }
        return remindAt != null;
    }

    /**
     * RELATIVE の場合のみ {@link #remindBeforeMinutes} の必須・正値を検証する。
     *
     * @return RELATIVE で remindBeforeMinutes が正の整数、または ABSOLUTE なら true
     */
    @AssertTrue(message = "相対指定リマインダーには開始何分前かの正の整数が必要です")
    public boolean isRemindBeforeMinutesValid() {
        if (effectiveKind() != ReminderKind.RELATIVE) {
            return true;
        }
        return remindBeforeMinutes != null && remindBeforeMinutes > 0;
    }
}
