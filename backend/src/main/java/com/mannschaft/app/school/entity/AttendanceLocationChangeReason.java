package com.mannschaft.app.school.entity;

/** 登校場所変更の理由を表す列挙型。 */
public enum AttendanceLocationChangeReason {
    /** 体調不良 */
    FELT_SICK,
    /** 負傷 */
    INJURY,
    /** メンタルヘルス */
    MENTAL_HEALTH,
    /** 予定（事前に計画された移動） */
    SCHEDULED,
    /** 回復（保健室から教室へ戻る等） */
    RECOVERED,
    /** 教室に戻った */
    RETURNED_TO_CLASS,
    /** その他 */
    OTHER
}
