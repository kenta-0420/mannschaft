package com.mannschaft.app.social.service;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import java.time.Instant;
import java.time.LocalDateTime;

/** socialドメインだけで読み取った受信申請の値。 */
public record IncomingTeamFollowRow(Long followId, Long sourceTeamId, boolean followedBack,
        Instant followedAt) {

    /** 既存JPA値のサーバー壁時計を、ゾーンを明示して同じ瞬間へ変換する。 */
    public IncomingTeamFollowRow(Long followId, Long sourceTeamId, boolean followedBack,
            LocalDateTime legacyFollowedAt) {
        this(followId, sourceTeamId, followedBack,
                legacyFollowedAt.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant());
    }
}
