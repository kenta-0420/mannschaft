package com.mannschaft.app.membership.dto;

import com.mannschaft.app.membership.domain.LeaveReason;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * メンバーシップ退会リクエスト DTO。
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §6.2 / §7.2</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class MembershipLeaveRequest {

    /** 退会理由。SELF / REMOVED / TRANSFER / OTHER。GDPR は専用ルートのため受け付けない。 */
    @NotNull
    private LeaveReason leaveReason;

    /** 除名（REMOVED）時に必須。除名を実行した管理者の userId。 */
    private Long removedBy;
}
