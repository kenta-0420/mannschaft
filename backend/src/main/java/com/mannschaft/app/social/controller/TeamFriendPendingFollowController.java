package com.mannschaft.app.social.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.social.dto.PendingTeamFollowListResponse;
import com.mannschaft.app.social.service.TeamFriendPendingFollowFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 実効権限を持つ利用者向けに、相互成立前のチームフォローを返す入口。 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/friends/pending")
@Tag(name = "フレンドチーム管理")
@RequiredArgsConstructor
public class TeamFriendPendingFollowController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final TeamFriendPendingFollowFacade pendingFollowFacade;

    @GetMapping
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "フレンド管理は実効権限で提供するコア機能であり、既存の一覧・取消と同じく機能フラグの対象外とする")
    @Operation(summary = "フォロー中チーム一覧取得",
            description = "MANAGE_FRIEND_TEAMS実効権限が必要。相互成立済みを除外し、公開有効相手だけ名前とslugを開示する。")
    public ResponseEntity<PendingTeamFollowListResponse> listPendingFollows(
            @PathVariable("teamId") Long teamId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        var pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return ResponseEntity.ok(pendingFollowFacade.getPendingFollows(
                teamId, SecurityUtils.getCurrentUserId(), pageable));
    }
}
