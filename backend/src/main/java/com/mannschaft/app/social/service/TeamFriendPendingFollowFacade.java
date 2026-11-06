package com.mannschaft.app.social.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.social.dto.PendingTeamFollowListResponse;
import com.mannschaft.app.social.dto.PendingTeamFollowView;
import com.mannschaft.app.social.dto.TeamFriendListResponse;
import com.mannschaft.app.team.service.TeamFollowTargetQueryService;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/** 認可・social読取・team概要読取をTXの外で段取りする入口。 */
@Service
@RequiredArgsConstructor
public class TeamFriendPendingFollowFacade {
    private final AccessControlService accessControlService;
    private final TeamFriendPendingFollowQueryService queryService;
    private final TeamFollowTargetQueryService targetQueryService;

    public PendingTeamFollowListResponse getPendingFollows(Long teamId, Long userId, Pageable pageable) {
        accessControlService.checkPermission(userId, teamId, "TEAM", "MANAGE_FRIEND_TEAMS");
        var rows = queryService.findPendingFollows(teamId, pageable);
        var ids = rows.getContent().stream().map(PendingTeamFollowRow::targetTeamId)
                .collect(Collectors.toSet());
        var summaries = targetQueryService.findDisclosableSummaries(ids);
        var views = rows.getContent().stream().map(row -> {
            var summary = summaries.get(row.targetTeamId());
            return new PendingTeamFollowView(row.followId(), row.targetTeamId(),
                    summary == null ? null : summary.name(), summary == null ? null : summary.slug(),
                    summary == null, row.followedBack(), row.followedAt());
        }).toList();
        return new PendingTeamFollowListResponse(views, TeamFriendListResponse.Pagination.builder()
                .page(rows.getNumber()).size(rows.getSize()).totalElements(rows.getTotalElements())
                .totalPages(rows.getTotalPages()).hasNext((long) rows.getNumber() + 1 < rows.getTotalPages()).build());
    }
}
