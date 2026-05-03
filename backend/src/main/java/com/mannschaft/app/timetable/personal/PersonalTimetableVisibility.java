package com.mannschaft.app.timetable.personal;

/**
 * 個人時間割の共有モード。
 *
 * <ul>
 *   <li>PRIVATE: 本人のみ閲覧可</li>
 *   <li>FAMILY_SHARED: personal_timetable_share_targets で指定した家族チームメンバーが閲覧可（コマのみ・メモ非公開）</li>
 * </ul>
 */
public enum PersonalTimetableVisibility {
    PRIVATE,
    FAMILY_SHARED
}
