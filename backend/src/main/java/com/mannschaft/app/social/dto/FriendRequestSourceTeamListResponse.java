package com.mannschaft.app.social.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** DB絞込み後の申請元候補と総数。 */
public record FriendRequestSourceTeamListResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FriendRequestSourceTeam> data,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TeamFriendListResponse.Pagination pagination) { }
