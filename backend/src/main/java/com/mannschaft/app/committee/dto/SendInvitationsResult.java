package com.mannschaft.app.committee.dto;

import com.mannschaft.app.committee.entity.CommitteeInvitationEntity;

import java.util.List;

/**
 * 招集送付結果。
 *
 * @param invitations                  送付した招集状一覧
 * @param skippedExistingMemberCount   既存メンバーのためスキップした件数
 * @param skippedPendingInvitationCount 既存未解決招集があるためスキップした件数
 */
public record SendInvitationsResult(
        List<CommitteeInvitationEntity> invitations,
        int skippedExistingMemberCount,
        int skippedPendingInvitationCount
) {
}
