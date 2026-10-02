package com.mannschaft.app.village.entity.enums;

/**
 * お祭りの参加表明（RSVP）の選択肢（F17.2 Wave2 ③お祭りの参加レイヤー・設計書 §5.2）。
 *
 * <ul>
 *   <li>{@link #GOING}  — 参加する</li>
 *   <li>{@link #MAYBE}  — たぶん参加する</li>
 * </ul>
 *
 * <p><b>ABSENT を持たない</b>【御裁可済み既定・設計書 §5.2】。祭の「不参加」は
 * 「無回答」と同じ扱い（RSVP レコードが無い＝答えていない）とし、欠席者一覧・欠席率を
 * DB レベルで構造的に作れないようにする（設計書 §10 ガードレール＝村人を追い立てない）。
 * 寄合（{@link VillageMeetupAttendanceStatus} は ABSENT を持つ）との非対称は意図的
 * （寄合は実務調整、祭は交流）。</p>
 */
public enum VillageFestivalRsvpStatus {
    GOING,
    MAYBE
}
