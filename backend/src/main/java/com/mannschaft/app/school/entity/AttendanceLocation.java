package com.mannschaft.app.school.entity;

/** 生徒の登校場所を表す列挙型。 */
public enum AttendanceLocation {
    /** 教室 */
    CLASSROOM,
    /** 保健室 */
    SICK_BAY,
    /** 別室（相談室・支援室等） */
    SEPARATE_ROOM,
    /** 図書室 */
    LIBRARY,
    /** オンライン参加 */
    ONLINE,
    /** 自宅学習 */
    HOME_LEARNING,
    /** 校外（校外学習・実習等） */
    OUT_OF_SCHOOL,
    /** 非該当（欠席・未登録等） */
    NOT_APPLICABLE
}
