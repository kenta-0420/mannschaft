package com.mannschaft.app.committee.dto;

import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 委員会メンバーレスポンス DTO。
 */
@Getter
@Builder
public class CommitteeMemberResponse {

    private Long userId;
    private CommitteeRole role;
    private LocalDateTime joinedAt;
    private LocalDateTime leftAt;
    private Long invitedBy;

    /**
     * CommitteeMemberEntity から CommitteeMemberResponse を生成する。
     */
    public static CommitteeMemberResponse of(CommitteeMemberEntity entity) {
        return CommitteeMemberResponse.builder()
                .userId(entity.getUserId())
                .role(entity.getRole())
                .joinedAt(entity.getJoinedAt())
                .leftAt(entity.getLeftAt())
                .invitedBy(entity.getInvitedBy())
                .build();
    }
}
