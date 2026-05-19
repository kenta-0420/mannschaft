package com.mannschaft.app.auth;

/**
 * F01.9 年齢確認・保護者同意機能で使用する年齢グループ区分。
 * 日本の学年制度（小学校低学年〜成人）に対応する。
 */
public enum AgeGroup {
    ELEMENTARY_LOWER,  // 小学校低学年（6〜7歳、小1・2年）
    ELEMENTARY_MIDDLE, // 小学校中学年（8〜9歳、小3・4年）
    ELEMENTARY_UPPER,  // 小学校高学年（10〜11歳、小5・6年）
    JUNIOR_HIGH,       // 中学生（12〜14歳）
    SENIOR_HIGH,       // 高校生（15〜17歳）
    ADULT              // 成人（18歳以上）
}
