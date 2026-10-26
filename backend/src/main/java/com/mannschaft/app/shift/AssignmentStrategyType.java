package com.mannschaft.app.shift;

/**
 * シフト自動割当のアルゴリズム種別。
 */
public enum AssignmentStrategyType {

    /** 手動割当 */
    MANUAL,

    /** 貪欲法 v1 */
    GREEDY_V1,

    /** CSP（制約充足問題）v1 */
    CSP_V1
}
