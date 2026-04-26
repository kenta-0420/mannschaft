package com.mannschaft.app.shift;

/**
 * シフト変更依頼の種別区分。
 */
public enum ChangeRequestType {

    /** A-1: 確定前変更依頼 */
    PRE_CONFIRM_EDIT,

    /** A-2: 個別交代依頼 */
    INDIVIDUAL_SWAP,

    /** A-3: オープンコール（不特定多数募集） */
    OPEN_CALL
}
