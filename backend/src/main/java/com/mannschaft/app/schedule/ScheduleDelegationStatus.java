package com.mannschaft.app.schedule;

/**
 * スケジュール代理出席委任状のステータス（F03.10）。
 *
 * <p>proxyvote ドメインの {@code DelegationStatus}（議決権委任）とは別物。
 * 代理出席ドメイン固有の状態遷移を表す（CLAUDE.md ドメイン境界の原則）。</p>
 */
public enum ScheduleDelegationStatus {
    /** 代理人の承認待ち（is_proxy_auto_accept = FALSE の場合のみ）。 */
    PENDING,
    /** 代理確定（自動承認または代理人が承認）。 */
    ACCEPTED,
    /** 代理人が拒否した。 */
    REJECTED,
    /** 委任者またはシステムが取り消した。 */
    CANCELLED
}
