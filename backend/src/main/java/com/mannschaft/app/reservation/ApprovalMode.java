package com.mannschaft.app.reservation;

/**
 * 予約スロットの承認モード。
 *
 * <p>F10.7: 業務アラート機能における予約管理の承認フローを制御する。</p>
 */
public enum ApprovalMode {
    /** 予約を自動承認する。 */
    AUTO,
    /** 管理者が手動で承認する。 */
    MANUAL
}
