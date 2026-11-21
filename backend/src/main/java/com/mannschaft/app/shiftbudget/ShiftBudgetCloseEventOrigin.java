package com.mannschaft.app.shiftbudget;

/** 締めイベントの由来。LEGACY_IMPORT は migration が旧締めを取り込んだ行（Commit の件数照合は CLOSE のみ）。 */
public enum ShiftBudgetCloseEventOrigin {
    CLOSE,
    LEGACY_IMPORT
}
