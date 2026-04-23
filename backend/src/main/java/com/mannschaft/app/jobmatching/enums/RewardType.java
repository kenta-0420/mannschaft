package com.mannschaft.app.jobmatching.enums;

/**
 * 求人の報酬タイプ。
 *
 * <p>MVP では {@link #LUMP_SUM}（一括払い）を主要に使用する。
 * 時給・日給は Phase 13.1.x で UI 対応予定。</p>
 */
public enum RewardType {

    /** 時給制 */
    HOURLY,

    /** 日給制 */
    DAILY,

    /** 一括払い（業務単位の固定報酬） */
    LUMP_SUM
}
