package com.mannschaft.app.shift;

/**
 * シフト希望の優先度区分（F03.5 v2: 5 段階）。
 *
 * <p>並び順は優先度の高（出勤したい）から低（絶対休み）へ。自動割当時のスコア計算で
 * この順序（ordinal）を参照する場合があるため、定義順の変更は慎重に行うこと。</p>
 *
 * <p>v1 の 3 段階（PREFERRED / AVAILABLE / UNAVAILABLE）は v2 で 5 段階に拡張された。
 * 旧 {@code UNAVAILABLE} は {@link #STRONG_REST} に移行済み（Flyway V3.137）。</p>
 */
public enum ShiftPreference {

    /** 希望（出勤したい）— 自動割当スコア目安: +100。 */
    PREFERRED,

    /** 可能（指定なし・出勤可能）— 自動割当スコア目安: 0。 */
    AVAILABLE,

    /** できれば出勤を避けたい（出れなくはない）— 自動割当スコア目安: -30。 */
    WEAK_REST,

    /** できれば休みたい（旧 UNAVAILABLE 相当）— 自動割当スコア目安: -80。 */
    STRONG_REST,

    /** 絶対休み（自動割当の対象外とする強い拒否）— 自動割当スコア目安: -∞（候補から除外）。 */
    ABSOLUTE_REST
}
