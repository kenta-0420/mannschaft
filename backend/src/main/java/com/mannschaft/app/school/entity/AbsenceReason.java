package com.mannschaft.app.school.entity;

/** 欠席・遅刻・早退の理由区分。 */
public enum AbsenceReason {
    /** 体調不良 */
    SICK,
    /** 怪我 */
    INJURY,
    /** 家庭の事情 */
    FAMILY_REASON,
    /** 忌引 */
    BEREAVEMENT,
    /** 出席停止（インフルエンザ等） */
    INFECTIOUS_DISEASE,
    /** 心身の不調 */
    MENTAL_HEALTH,
    /** 公的活動 */
    OFFICIAL_BUSINESS,
    /** その他（コメント必須） */
    OTHER
}
