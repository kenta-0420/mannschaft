package com.mannschaft.app.loyalty;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Clockの瞬間を保持し、営業日と日末だけをJSTへ明示変換する。 */
@Component
@RequiredArgsConstructor
public class LoyaltyTime {
    private final Clock clock;

    public Instant now() {
        // DATETIME(0)への丸めで日末のcreated_atがexpires_atへ繰り上がることを防ぐ。
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    public LocalDate today() {
        return LocalDate.ofInstant(now(), UserZoneLocalDateTimeParser.SERVER_ZONE);
    }

    public Instant expiresAt(Instant createdAt) {
        Instant ttl = createdAt.plus(30, ChronoUnit.MINUTES);
        Instant dayEnd = LocalDate.ofInstant(createdAt, UserZoneLocalDateTimeParser.SERVER_ZONE)
                .plusDays(1).atStartOfDay(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant();
        return ttl.isBefore(dayEnd) ? ttl : dayEnd;
    }
}
