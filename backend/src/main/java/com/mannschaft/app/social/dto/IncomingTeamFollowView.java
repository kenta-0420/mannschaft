package com.mannschaft.app.social.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 受信した未成立申請。非開示元の名前とslugも必須フィールドとしてnullを返す。 */
public record IncomingTeamFollowView(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long followId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long sourceTeamId,
        @Schema(types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED) String sourceTeamName,
        @Schema(types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED) String sourceTeamSlug,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean nameHidden,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean followedBack,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant followedAt) { }
