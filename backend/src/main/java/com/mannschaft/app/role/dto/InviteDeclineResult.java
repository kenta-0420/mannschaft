package com.mannschaft.app.role.dto;

/**
 * 辞退の結果。{@code newlyDeclined} が true のときだけ呼び出し側が監査を記録する
 * （既に失効済みの再辞退は冪等に何もしない）。
 */
public record InviteDeclineResult(
        boolean newlyDeclined,
        Long tokenId,
        Long targetUserId,
        Long teamId,
        Long organizationId) {
}
