package com.mannschaft.app.shiftbudget;

/** 締めを起動した主体の種別（締めイベントの actor_kind。旧締めの取り込み行は UNKNOWN）。 */
public enum ShiftBudgetCloseActorKind {
    MANUAL,
    BATCH,
    UNKNOWN
}
