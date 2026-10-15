package com.mannschaft.app.safetycheck.entity;

/**
 * 安否確認の発生源種別。
 *
 * <p>F09.16 居住実態管理からの連携（ORG_WIDE）と通常の手動発信（MANUAL）を区別する。</p>
 */
public enum SafetyCheckSourceType {
    /** 手動（通常の安否確認）。F03.6 の既存フロー。 */
    MANUAL,
    /** 管理組合一斉（F09.16 居住実態管理からの連携）。 */
    ORG_WIDE
}
