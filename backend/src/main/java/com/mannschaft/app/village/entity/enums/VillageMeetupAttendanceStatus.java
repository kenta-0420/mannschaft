package com.mannschaft.app.village.entity.enums;

/**
 * 寄合出欠の選択肢（F17.2 Wave1 ②寄合後半戦）。
 *
 * <ul>
 *   <li>{@link #GOING}  — 行く</li>
 *   <li>{@link #MAYBE}  — たぶん行く</li>
 *   <li>{@link #ABSENT} — 行けない</li>
 * </ul>
 *
 * <p>寄合は「実務調整」機能であり、幹事が席・資料の数を把握できるよう
 * 明示的な欠席（ABSENT）を持つ（設計書 §10.1・祭 RSVP との非対称の正当化）。
 * ただし ABSENT は当該寄合の調整目的のみで、村横断の欠席率集計は行わない（G3）。</p>
 */
public enum VillageMeetupAttendanceStatus {
    GOING,
    MAYBE,
    ABSENT
}
