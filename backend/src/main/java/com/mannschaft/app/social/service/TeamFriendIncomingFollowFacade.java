package com.mannschaft.app.social.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.social.dto.IncomingTeamFollowListResponse;
import com.mannschaft.app.social.dto.IncomingTeamFollowView;
import com.mannschaft.app.social.dto.TeamFriendListResponse;
import com.mannschaft.app.team.service.TeamFollowTargetQueryService;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/** 認可・social読取・team概要読取をTXの外で段取りする入口。 */
@Service
@RequiredArgsConstructor
public class TeamFriendIncomingFollowFacade {
    private final AccessControlService accessControlService;
    private final TeamFriendIncomingFollowQueryService queryService;
    private final TeamFollowTargetQueryService targetQueryService;

    public IncomingTeamFollowListResponse getIncomingFollows(Long teamId, Long userId, Pageable pageable) {
        accessControlService.checkPermission(userId, teamId, "TEAM", "MANAGE_FRIEND_TEAMS");
        var rows = queryService.findIncomingFollows(teamId, pageable);
        var ids = rows.getContent().stream().map(IncomingTeamFollowRow::sourceTeamId)
                .collect(Collectors.toSet());
        var summaries = targetQueryService.findDisclosableSummaries(ids);
        var views = rows.getContent().stream().map(row -> {
            var summary = summaries.get(row.sourceTeamId());
            return new IncomingTeamFollowView(row.followId(), row.sourceTeamId(),
                    summary == null ? null : summary.name(), summary == null ? null : summary.slug(),
                    summary == null, row.followedBack(), row.followedAt());
        }).toList();
        return new IncomingTeamFollowListResponse(views, TeamFriendListResponse.Pagination.builder()
                .page(rows.getNumber()).size(rows.getSize()).totalElements(rows.getTotalElements())
                .totalPages(rows.getTotalPages()).hasNext((long) rows.getNumber() + 1 < rows.getTotalPages()).build());
    }
}
