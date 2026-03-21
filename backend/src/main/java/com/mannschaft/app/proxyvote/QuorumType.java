package com.mannschaft.app.proxyvote;

/**
 * 定足数算出方式。
 */
public enum QuorumType {
    /** 過半数 */
    MAJORITY,
    /** 2/3 以上 */
    TWO_THIRDS,
    /** カスタム */
    CUSTOM
}
