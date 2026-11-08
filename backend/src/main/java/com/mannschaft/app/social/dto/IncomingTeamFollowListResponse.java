package com.mannschaft.app.social.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 受信未成立申請の一覧。伏せ字の行も総数に含める。 */
public record IncomingTeamFollowListResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<IncomingTeamFollowView> data,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TeamFriendListResponse.Pagination pagination) { }
