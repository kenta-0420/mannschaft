package com.mannschaft.app.shiftbudget;

/**
 * F08.7 シフト予算消化記録のキャンセル理由。
 *
 * <p>設計書 F08.7 (v1.2) §5.3 / §11 / §11.1 に準拠。
 * {@link ShiftBudgetConsumptionStatus#CANCELLED} 遷移時に必須セットする。</p>
 */
public enum ShiftBudgetCancelReason {

    /** シフト全体が論理削除された */
    SHIFT_DELETED,

    /** 個別スロットが削除された */
    SLOT_REMOVED,

    /** スロットの assigned_user_ids からユーザーが外された */
    USER_UNASSIGNED,

    /** 管理者による手動キャンセル（経理判断） */
    MANUAL_REVERSE,

    /** シフトの取消→再公開で旧 PLANNED が CANCELLED 化された */
    RE_PUBLISHED,

    /** 月次締め後の遅延キャンセル（取消仕訳パターン） */
    POST_CLOSE_REVERSE
}
