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
    /** 表示名（組織内のため実名: 姓 名）。FE がアバターの頭文字を取るため null にしない。 */
    private String displayName;
    /** アバター表示 URL（署名付き）。未設定の場合は null。 */
    private String avatarUrl;
    private CommitteeRole role;
    private LocalDateTime joinedAt;
    private LocalDateTime leftAt;
    private Long invitedBy;

    /**
     * CommitteeMemberEntity から CommitteeMemberResponse を生成する。
     *
     * @param displayName 表示名（null 不可）
     * @param avatarUrl   署名付きアバター表示 URL（未設定なら null）
     */
    public static CommitteeMemberResponse of(CommitteeMemberEntity entity, String displayName, String avatarUrl) {
        return CommitteeMemberResponse.builder()
                .userId(entity.getUserId())
                .displayName(displayName)
                .avatarUrl(avatarUrl)
                .role(entity.getRole())
                .joinedAt(entity.getJoinedAt())
                .leftAt(entity.getLeftAt())
                .invitedBy(entity.getInvitedBy())
                .build();
    }
}
