package com.mannschaft.app.event.entity;

/**
 * イベント出席管理モードの列挙型。
 * NONE=自由参加、RSVP=出欠確認、REGISTRATION=参加登録（既存）
 */
public enum EventAttendanceMode {
    /** 自由参加（出欠管理なし） */
    NONE,
    /** 出欠確認（RSVP形式） */
    RSVP,
    /** 参加登録（既存の登録フォーム形式） */
    REGISTRATION
}
