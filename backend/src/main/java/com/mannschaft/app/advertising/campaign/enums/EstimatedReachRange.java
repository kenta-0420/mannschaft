package com.mannschaft.app.advertising.campaign.enums;

/**
 * F09.17 推定リーチのレンジ enum。
 *
 * <p>設計書 §9 解決済み事項 #4「有効数字 1 桁レンジ・100 人未満は非表示」に基づき、
 * 個別ユーザー特定リスクを避けるため正確な人数を返さず、必ずレンジで返す。</p>
 *
 * <p>100 人未満は {@link #UNDER_100} を返し、フロント側で「条件に合致するユーザーが少なすぎます」
 * と表示する（リーチ数値そのものは出さない）。</p>
 */
public enum EstimatedReachRange {
    /** 100 人未満（非表示推奨：個別特定リスク回避） */
    UNDER_100("約100人未満"),
    /** 100〜499 人 */
    RANGE_100_500("約100〜500人"),
    /** 500〜999 人 */
    RANGE_500_1K("約500〜1,000人"),
    /** 1,000〜4,999 人 */
    RANGE_1K_5K("約1,000〜5,000人"),
    /** 5,000〜9,999 人 */
    RANGE_5K_10K("約5,000〜10,000人"),
    /** 10,000〜49,999 人 */
    RANGE_10K_50K("約10,000〜50,000人"),
    /** 50,000〜99,999 人 */
    RANGE_50K_100K("約50,000〜100,000人"),
    /** 100,000 人以上 */
    OVER_100K("約100,000人以上");

    private final String label;

    EstimatedReachRange(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 概算ユーザー数からレンジを決定する。
     *
     * @param count 推定人数（>= 0）
     * @return 対応するレンジ
     */
    public static EstimatedReachRange fromCount(long count) {
        if (count < 100) {
            return UNDER_100;
        }
        if (count < 500) {
            return RANGE_100_500;
        }
        if (count < 1_000) {
            return RANGE_500_1K;
        }
        if (count < 5_000) {
            return RANGE_1K_5K;
        }
        if (count < 10_000) {
            return RANGE_5K_10K;
        }
        if (count < 50_000) {
            return RANGE_10K_50K;
        }
        if (count < 100_000) {
            return RANGE_50K_100K;
        }
        return OVER_100K;
    }
}
