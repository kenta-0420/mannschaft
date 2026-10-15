package com.mannschaft.app.membership.domain;

/**
 * 退会理由。
 *
 * <p>F00.5 メンバーシップ基盤再設計で導入。memberships.leave_reason の ENUM 値と一致する。
 * left_at と同時に必須（CHECK 制約 chk_memberships_left_reason により DB 側で保証）。</p>
 *
 * <ul>
 *   <li>{@link #SELF}: 自主退会</li>
 *   <li>{@link #REMOVED}: 管理者による除名</li>
 *   <li>{@link #GDPR}: GDPR 削除リクエストによるマスキング退会</li>
 *   <li>{@link #TRANSFER}: 組織内・チーム間の異動による退会</li>
 *   <li>{@link #OTHER}: その他</li>
 *   <li>{@link #DECEASED}: 逝去による退会（F14.3）</li>
 *   <li>{@link #RELOCATED}: 転出による退会（F14.3）</li>
 * </ul>
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §5.1 / §7.2</p>
 * <p>DECEASED / RELOCATED は docs/features/F14.3_resident_life_events.md §5.4 で追加。</p>
 */
public enum LeaveReason {

    /** 自主退会。 */
    SELF,

    /** 管理者による除名。 */
    REMOVED,

    /** GDPR 削除によるマスキング退会。 */
    GDPR,

    /** 組織異動・チーム間異動による退会。 */
    TRANSFER,

    /** その他。 */
    OTHER,

    /** 逝去による退会（F14.3）。 */
    DECEASED,

    /** 転出による退会（F14.3）。 */
    RELOCATED
}
