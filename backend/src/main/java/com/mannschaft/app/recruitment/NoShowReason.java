package com.mannschaft.app.recruitment;

public enum NoShowReason {
    /** 管理者による手動マーク */
    ADMIN_MARKED,
    /** 開催後24h経過後の自動検出 */
    AUTO_DETECTED
}
