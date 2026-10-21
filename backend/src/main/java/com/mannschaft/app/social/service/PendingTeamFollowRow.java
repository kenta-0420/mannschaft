package com.mannschaft.app.social.service;

import java.time.LocalDateTime;

/** socialドメインだけで読み取ったフォロー中の値。 */
public record PendingTeamFollowRow(Long followId, Long targetTeamId, boolean followedBack,
        LocalDateTime followedAt) {
}
