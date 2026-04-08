package com.mannschaft.app.recruitment;

/**
 * F03.11 参加者ステータス遷移の理由種別。
 * recruitment_participant_history.change_reason に格納される。
 */
public enum ParticipantHistoryReason {
    /** ユーザー本人の操作 */
    USER_ACTION,
    /** キャンセル待ちからの自動昇格 (Phase 3) */
    AUTO_PROMOTE,
    /** 最小定員未達による自動キャンセル (Phase 3) */
    AUTO_CANCEL,
    /** 管理者操作 */
    ADMIN_ACTION,
    /** システム自動 */
    SYSTEM
}
