package com.mannschaft.app.schedule.dto;

import com.mannschaft.app.schedule.ReminderKind;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.OffsetDateTime;

/**
 * リマインダー作成リクエストDTO（機能55 第二陣で相対/絶対両対応）。
 *
 * <p>{@link #reminderKind} が {@link ReminderKind#ABSOLUTE}（既定）の場合は {@link #remindAt}（絶対日時）を、
 * {@link ReminderKind#RELATIVE} の場合は {@link #remindBeforeMinutes}（開始N分前）を使用する。
 * フィールド単体の {@code @NotNull}/{@code @Future} ではなく、kind に応じた相互排他検証を
 * クラスレベルの {@link AssertTrue} で行う（既存の絶対指定との後方互換のため既定は ABSOLUTE）。</p>
 *
 * <p>{@link #remindAt} は {@link OffsetDateTime} で受け取り、タイムゾーン情報を保持する。
 * FE はユーザーのローカルタイムゾーンのオフセットを付与して送信すること（例: 2026-06-04T08:00:00+09:00）。
 * BE はサービス層で JVM TZ（Asia/Tokyo）へ変換してから LocalDateTime として保存する。</p>
 */
@Getter
@RequiredArgsConstructor
public class CreateReminderRequest {

    /**
     * 絶対日時（{@link ReminderKind#ABSOLUTE} 時に必須）。
     * OffsetDateTime で受け取ることでクライアントのタイムゾーンを正確に把握する。
     * 過去日時は {@link #isRemindAtValid()} で拒否する。
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
     * ABSOLUTE の場合のみ {@link #remindAt} の必須・未来日時を検証する。
     * OffsetDateTime は UTC 基準で比較するため、タイムゾーンに依らず正確に未来判定できる。
     *
     * @return ABSOLUTE で remindAt が未来日時、または RELATIVE で remindAt 未指定なら true
     */
    @AssertTrue(message = "絶対指定リマインダーには未来の日時が必要です")
    public boolean isRemindAtValid() {
        if (effectiveKind() != ReminderKind.ABSOLUTE) {
            // RELATIVE 時は remindAt を使わない（指定があっても無視するため検証対象外）
            return true;
        }
        return remindAt != null && remindAt.isAfter(OffsetDateTime.now());
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
