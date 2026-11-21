package com.mannschaft.app.shiftbudget.service;

/**
 * 発見（通常 SELECT）後にロック対象の割当の集合が変わった（CMP-260909-2152 確定設計 r12 §4.1）。TX 全体をロールバックし、
 * 呼び出し側が計上先の解決からやり直す（追加でロックを取らない。最大3回）。
 */
public class ShiftBudgetLockSetChangedException extends RuntimeException {

    public ShiftBudgetLockSetChangedException(String message) {
        super(message);
    }
}
