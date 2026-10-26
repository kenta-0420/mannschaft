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

    /** 希望（出勤したい）— 自動割当スコア: +100（設計書 F03.5 v2.3 §5.10 準拠）。 */
    PREFERRED,

    /** 可能（指定なし・出勤可能）— 自動割当スコア: 0。 */
    AVAILABLE,

    /** できれば出勤を避けたい（出れなくはない）— 自動割当スコア: -30（設計書 F03.5 v2.3 §5.10 準拠）。 */
    WEAK_REST,

    /** できれば休みたい（旧 UNAVAILABLE 相当）— 自動割当スコア: -80。 */
    STRONG_REST,

    /** 絶対休み（自動割当の対象外とする強い拒否）— 自動割当スコア: -∞（候補から除外）。 */
    ABSOLUTE_REST;

    /**
     * 自動割当の候補除外フラグ。{@link #ABSOLUTE_REST} のみ候補から完全除外される。
     *
     * @return {@code true} = 候補から完全除外 / {@code false} = スコア計算対象
     */
    public boolean isHardExcluded() {
        return this == ABSOLUTE_REST;
    }

    /** {@link #isHardExcluded()} の逆。割当可能かどうかを返す（互換用）。 */
    public boolean isAssignable() {
        return !isHardExcluded();
    }

    /**
     * 自動割当スコアリング用の評価点。値が大きいほどアサイン優先度が高い。
     *
     * <p>PREFERRED=+100 / AVAILABLE=0 / WEAK_REST=-30 / STRONG_REST=-80 / ABSOLUTE_REST=-∞。
     * 設計書 F03.5 v2.3 §5.10 準拠。</p>
     *
     * @return スコア値
     */
    public int toAssignmentScore() {
        return switch (this) {
            case PREFERRED -> 100;
            case AVAILABLE -> 0;
            case WEAK_REST -> -30;
            case STRONG_REST -> -80;
            case ABSOLUTE_REST -> Integer.MIN_VALUE;
        };
    }
}
