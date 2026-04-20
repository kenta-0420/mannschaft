package com.mannschaft.app.event.entity;

/**
 * イベント公開範囲を表す列挙型。
 */
public enum EventVisibility {
    /** 外部（一般）公開 */
    PUBLIC,
    /** サポーター以上に公開 */
    SUPPORTERS_AND_ABOVE,
    /** メンバーのみ */
    MEMBERS_ONLY
}
