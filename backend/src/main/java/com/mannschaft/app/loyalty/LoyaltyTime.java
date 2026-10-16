package com.mannschaft.app.loyalty;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** UTC Clockをアプリ層のJST壁時計へ明示変換する。 */
@Component
@RequiredArgsConstructor
public class LoyaltyTime {
    private final Clock clock;

    public LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(UserZoneLocalDateTimeParser.SERVER_ZONE));
    }

    public LocalDate today() {
        return now().toLocalDate();
    }

    public LocalDateTime expiresAt(LocalDateTime createdAt) {
        LocalDateTime ttl = createdAt.plusMinutes(30);
        LocalDateTime dayEnd = createdAt.toLocalDate().plusDays(1).atStartOfDay();
        return ttl.isBefore(dayEnd) ? ttl : dayEnd;
    }
}
