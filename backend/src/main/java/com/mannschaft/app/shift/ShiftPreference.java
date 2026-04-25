package com.mannschaft.app.shift;

/**
 * シフト希望の優先度区分（5段階）。
 * V3.137 マイグレーションで旧 UNAVAILABLE は STRONG_REST に移行済み。
 */
public enum ShiftPreference {

    /** 希望（出勤したい） */
    PREFERRED(100, "希望"),

    /** 可能（出勤可能） */
    AVAILABLE(50, "可能"),

    /** やや休みたい */
    WEAK_REST(10, "やや休希望"),

    /** 休みたい */
    STRONG_REST(0, "休希望"),

    /** 絶対不可（割当不可） */
    ABSOLUTE_REST(-1, "絶対不可");

    private final int score;
    private final String label;

    ShiftPreference(int score, String label) {
        this.score = score;
        this.label = label;
    }

    /** 割当スコアを返す。 */
    public int getScore() {
        return score;
    }

    /** 日本語ラベルを返す。 */
    public String getLabel() {
        return label;
    }

    /** 割当可能かどうかを返す（ABSOLUTE_REST のみ false）。 */
    public boolean isAssignable() {
        return this != ABSOLUTE_REST;
    }
}
