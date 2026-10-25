package com.mannschaft.app.social.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.role.service.FriendRequestSourceTeamQueryService;
import com.mannschaft.app.social.dto.FollowTeamRequest;
import com.mannschaft.app.social.dto.FollowTeamResponse;
import com.mannschaft.app.social.dto.FriendRequestSourceTeam;
import com.mannschaft.app.social.dto.FriendRequestSourceTeamListResponse;
import com.mannschaft.app.social.dto.TeamFriendListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/** roleの現在状態確認と既存social更新を、越境TXを作らず段取りする申請入口。 */
@Service
@RequiredArgsConstructor
public class TeamFriendRequestFacade {
    private final FriendRequestSourceTeamQueryService sourceQueryService;
    private final TeamFriendsService teamFriendsService;
    private final TeamFriendFollowBySlugService followBySlugService;

    /** 候補は本人所属のSQLページだけを受け取り、レスポンス値へ変換する。 */
    public FriendRequestSourceTeamListResponse getSourceTeams(Long userId, String targetSlug, Pageable pageable) {
        var rows = sourceQueryService.findSourceTeams(userId, targetSlug, pageable);
        var views = rows.getContent().stream().map(row -> new FriendRequestSourceTeam(
                row.teamId(), row.teamSlug(), row.teamName())).toList();
        return new FriendRequestSourceTeamListResponse(views, TeamFriendListResponse.Pagination.builder()
                .page(rows.getNumber()).size(rows.getSize()).totalElements(rows.getTotalElements())
                .totalPages(rows.getTotalPages()).hasNext((long) rows.getNumber() + 1 < rows.getTotalPages()).build());
    }

    /** 元失効を相手の存在確認より先に拒否し、既存成立・通知・業務TXはそのまま用いる。 */
    public FollowTeamResponse follow(Long teamId, FollowTeamRequest request, Long userId) {
        if (!sourceQueryService.isEligibleSource(userId, teamId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return request.getTargetTeamSlug() != null
                ? followBySlugService.followBySlug(teamId, request.getTargetTeamSlug(), userId)
                : teamFriendsService.follow(teamId, request.getTargetTeamId(), userId);
    }
}
