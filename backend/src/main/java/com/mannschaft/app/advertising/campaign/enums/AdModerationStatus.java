package com.mannschaft.app.advertising.campaign.enums;

/**
 * F09.17 キャンペーン審査状態。
 *
 * <ul>
 *   <li>PENDING: 自動検知未実行</li>
 *   <li>AUTO_PASSED: 自動 NG 検知をすり抜け SYSTEM_ADMIN レビュー待ち</li>
 *   <li>AUTO_FLAGGED: 自動 NG 検知でフラグ付き</li>
 *   <li>APPROVED: SYSTEM_ADMIN 承認済</li>
 *   <li>BLOCKED: SYSTEM_ADMIN ブロック / 自動 SUSPEND</li>
 * </ul>
 */
public enum AdModerationStatus {
    PENDING,
    AUTO_PASSED,
    AUTO_FLAGGED,
    APPROVED,
    BLOCKED
}
