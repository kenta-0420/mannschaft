package com.mannschaft.app.school.entity;

/** 出席要件規程のカテゴリを表す列挙型。 */
public enum RequirementCategory {
    /** 進級要件 */
    GRADE_PROMOTION,
    /** 卒業要件 */
    GRADUATION,
    /** 教科単位認定要件 */
    SUBJECT_CREDIT,
    /** 皆勤賞 */
    PERFECT_ATTENDANCE,
    /** 自由設定 */
    CUSTOM
}
