package com.mannschaft.app.committee.dto;

import com.mannschaft.app.committee.entity.CommitteeInvitationEntity;
import com.mannschaft.app.committee.entity.CommitteeInvitationResolution;
import com.mannschaft.app.committee.entity.CommitteeRole;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 委員会招集状レスポンス DTO。
 */
@Getter
@Builder
public class CommitteeInvitationResponse {

    private Long id;
    private Long committeeId;
    private Long inviteeUserId;
    private CommitteeRole proposedRole;
    private String inviteToken;
    private LocalDateTime expiresAt;
    private LocalDateTime resolvedAt;
    private CommitteeInvitationResolution resolution;
    /** 招集状の状態: "PENDING" / "ACCEPTED" / "DECLINED" / "EXPIRED" / "CANCELLED" */
    private String invitationStatus;
    private LocalDateTime createdAt;

    /**
     * CommitteeInvitationEntity から CommitteeInvitationResponse を生成する。
     */
    public static CommitteeInvitationResponse of(CommitteeInvitationEntity entity) {
        String status;
        if (entity.getResolvedAt() == null) {
            // 未解決の場合、有効期限を確認して状態を決定
            if (entity.getExpiresAt().isBefore(LocalDateTime.now())) {
                status = "EXPIRED";
            } else {
                status = "PENDING";
            }
        } else {
            status = entity.getResolution() != null
                    ? entity.getResolution().name()
                    : "PENDING";
        }

        return CommitteeInvitationResponse.builder()
                .id(entity.getId())
                .committeeId(entity.getCommitteeId())
                .inviteeUserId(entity.getInviteeUserId())
                .proposedRole(entity.getProposedRole())
                .inviteToken(entity.getInviteToken())
                .expiresAt(entity.getExpiresAt())
                .resolvedAt(entity.getResolvedAt())
                .resolution(entity.getResolution())
                .invitationStatus(status)
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
