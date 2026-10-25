package com.mannschaft.app.social.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 操作者が現在申請元にできるチーム。全項目は非null。 */
public record FriendRequestSourceTeam(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long teamId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String teamSlug,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String teamName) { }
