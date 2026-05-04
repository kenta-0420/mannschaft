package com.mannschaft.app.timetable.personal;

/**
 * 個人時間割のステータス。
 *
 * <p>遷移ルール:</p>
 * <ul>
 *   <li>DRAFT → ACTIVE: 同一ユーザーの期間重複 ACTIVE は自動 ARCHIVED</li>
 *   <li>ACTIVE → ARCHIVED: 手動</li>
 *   <li>ARCHIVED → DRAFT: 手動</li>
 *   <li>不正な遷移は 409</li>
 * </ul>
 */
public enum PersonalTimetableStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED
}

