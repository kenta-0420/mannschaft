package com.mannschaft.app.schedule;

/** 共有予定を誰の予定として扱うかを表す対象者モード。 */
public enum ScheduleTargetMode {
    /** スコープ内の全メンバーを対象とする（既存予定の後方互換値）。 */
    ALL_MEMBERS,
    /** 明示的に指定したスコープ内メンバーだけを対象とする。 */
    SELECTED_MEMBERS
}
