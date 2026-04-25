package com.mannschaft.app.event;

/**
 * チェックイン種別。チェックインの実行方式を表す。
 */
public enum CheckinType {
    STAFF_SCAN,
    SELF,
    /** 点呼（個別）。F03.12 ケア対象者見守り通知で使用。 */
    ROLL_CALL,
    /** 点呼（一括）。F03.12 ケア対象者見守り通知で使用。 */
    ROLL_CALL_BATCH
}
