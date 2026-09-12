package com.mannschaft.app.shift.service;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 同一人物の勤務が時間的に重なるかを判定するユーティリティ。
 *
 * <p><b>本クラスは試練（テスト先行）が置いた未実装スタブである。</b>
 * 判定本体は出陣（実装）で書く。現状はいずれのメソッドも
 * {@link UnsupportedOperationException} を投げるため、
 * {@code ShiftAssignmentOverlapDetectorTest} は全件 red となる。</p>
 *
 * <p>満たすべき規則（設計 F03.5 §11.3.5）:</p>
 * <ol>
 *   <li>半開区間 {@code [start, end)} で判定する（隣接は重ならない）</li>
 *   <li>日跨ぎは {@code endsNextDay} を展開し {@code LocalDateTime} 区間で比較する</li>
 * </ol>
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
        throw new UnsupportedOperationException("未実装（試練のスタブ）: 設計 F03.5 §11.3.5 の重なり判定");
    }
}
