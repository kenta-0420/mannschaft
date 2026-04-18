package com.mannschaft.app.committee.dto;

import com.mannschaft.app.committee.entity.CommitteeRole;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 委員会招集状送付リクエスト DTO。
 */
@Getter
@Setter
@NoArgsConstructor
public class CommitteeInviteRequest {

    /** 被招集者ユーザーIDリスト */
    @NotEmpty
    @Size(max = 100)
    private List<Long> inviteeUserIds;

    /** 提案ロール（省略時は MEMBER） */
    private CommitteeRole proposedRole;

    /** メッセージ（任意） */
    @Size(max = 1000)
    private String message;

    /** 有効期間（日数、省略時は 7 日） */
    @Min(1)
    @Max(30)
    private Integer expiresInDays;
}
