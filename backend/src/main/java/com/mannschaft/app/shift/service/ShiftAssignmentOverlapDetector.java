package com.mannschaft.app.shift.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 同一人物の勤務が時間的に重なるかを判定するユーティリティ。
 *
 * <p>判定規則（設計 F03.5 §11.3.5）:</p>
 * <ol>
 *   <li>半開区間 {@code [start, end)} で判定する（隣接は重ならない。15:00 終了と 15:00 開始は衝突ではない）</li>
 *   <li>日跨ぎは {@code endsNextDay} を展開し {@code LocalDateTime} 区間で比較する</li>
 * </ol>
 *
 * <p>状態を持たないため {@code static} なユーティリティとする（DI する意味が無く、
 * 単体テストから直接叩けることを優先した）。</p>
 */
public final class ShiftAssignmentOverlapDetector {

    private ShiftAssignmentOverlapDetector() {
        // ユーティリティクラス
    }

    /**
     * 2 つの勤務時間帯が重なるか判定する。
     *
     * @param leftDate         左の枠の日付
     * @param leftStart        左の枠の開始時刻
     * @param leftEnd          左の枠の終了時刻
     * @param leftEndsNextDay  左の枠が翌日終了なら true
     * @param rightDate        右の枠の日付
     * @param rightStart       右の枠の開始時刻
     * @param rightEnd         右の枠の終了時刻
     * @param rightEndsNextDay 右の枠が翌日終了なら true
     * @return 重なるなら true
     */
    public static boolean overlaps(
            LocalDate leftDate, LocalTime leftStart, LocalTime leftEnd, boolean leftEndsNextDay,
            LocalDate rightDate, LocalTime rightStart, LocalTime rightEnd, boolean rightEndsNextDay) {
        LocalDateTime leftFrom = LocalDateTime.of(leftDate, leftStart);
        LocalDateTime leftTo = endOf(leftDate, leftStart, leftEnd, leftEndsNextDay);
        LocalDateTime rightFrom = LocalDateTime.of(rightDate, rightStart);
        LocalDateTime rightTo = endOf(rightDate, rightStart, rightEnd, rightEndsNextDay);

        // 半開区間 [from, to) の交差判定。端点の一致（隣接）は重なりとしない。
        return leftFrom.isBefore(rightTo) && rightFrom.isBefore(leftTo);
    }

    /**
     * 終了時刻を {@code LocalDateTime} へ展開する。
     *
     * <p>{@code endsNextDay} が true の枠は翌日終了として +1 日する。
     * フラグが false でも {@code endTime <= startTime} という移行前の行が残りうるため、
     * その場合も翌日終了とみなす（そう解釈しないと長さ 0 以下の区間になり、
     * 実際には重なっている勤務を取りこぼす）。</p>
     */
    private static LocalDateTime endOf(LocalDate date, LocalTime start, LocalTime end, boolean endsNextDay) {
        boolean nextDay = endsNextDay || !end.isAfter(start);
        return LocalDateTime.of(date, end).plusDays(nextDay ? 1 : 0);
    }
}
