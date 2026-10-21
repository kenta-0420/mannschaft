package com.mannschaft.app.social.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** フォロー中一覧の値。開示できない相手の名前とslugはnullにする。 */
public record PendingTeamFollowView(Long followId, Long targetTeamId,
        @Schema(nullable = true) String targetTeamName,
        @Schema(nullable = true) String targetTeamSlug, boolean nameHidden, boolean followedBack,
        LocalDateTime followedAt) {
}
