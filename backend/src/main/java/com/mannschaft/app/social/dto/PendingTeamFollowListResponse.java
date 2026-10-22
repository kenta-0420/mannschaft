package com.mannschaft.app.social.dto;

import java.util.List;

/** フォロー中一覧。既存フレンド一覧と同じページング契約を持つ。 */
public record PendingTeamFollowListResponse(List<PendingTeamFollowView> data,
        TeamFriendListResponse.Pagination pagination) {
}
