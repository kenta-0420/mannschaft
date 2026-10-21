package com.mannschaft.app.social.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.social.dto.PendingTeamFollowListResponse;
import com.mannschaft.app.team.service.TeamFollowTargetQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/** 認可・social読取・team概要読取をTXの外で段取りする入口。試練先行の型骨格。 */
@Service
@RequiredArgsConstructor
public class TeamFriendPendingFollowFacade {
    private final AccessControlService accessControlService;
    private final TeamFriendPendingFollowQueryService queryService;
    private final TeamFollowTargetQueryService targetQueryService;

    public PendingTeamFollowListResponse getPendingFollows(Long teamId, Long userId, Pageable pageable) {
        throw new UnsupportedOperationException("CMP-261021-0020 試練先行の未実装");
    }
}
