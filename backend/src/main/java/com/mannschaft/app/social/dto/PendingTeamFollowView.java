package com.mannschaft.app.social.dto;

import java.time.LocalDateTime;

/** フォロー中一覧の値。開示できない相手の名前とslugはnullにする。 */
public record PendingTeamFollowView(Long followId, Long targetTeamId, String targetTeamName,
        String targetTeamSlug, boolean nameHidden, boolean followedBack, LocalDateTime followedAt) {
}
