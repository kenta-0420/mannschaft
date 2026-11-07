package com.mannschaft.app.social.service;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import java.time.Instant;
import java.time.LocalDateTime;

/** socialドメインだけで読み取ったフォロー中の値。 */
public record PendingTeamFollowRow(Long followId, Long targetTeamId, boolean followedBack,
        Instant followedAt) {

    /** 既存JPA値のサーバー壁時計を、ゾーンを明示して同じ瞬間へ変換する。 */
    public PendingTeamFollowRow(Long followId, Long targetTeamId, boolean followedBack,
            LocalDateTime legacyFollowedAt) {
        this(followId, targetTeamId, followedBack,
                legacyFollowedAt.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant());
    }
}
