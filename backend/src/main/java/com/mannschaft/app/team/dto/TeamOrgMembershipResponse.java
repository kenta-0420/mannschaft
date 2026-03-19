package com.mannschaft.app.team.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * チーム・組織メンバーシップレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class TeamOrgMembershipResponse {

    private final Long id;
    private final Long teamId;
    private final String teamName;
    private final Long organizationId;
    private final String organizationName;
    private final String status;
    private final LocalDateTime invitedAt;
    private final LocalDateTime respondedAt;
}
