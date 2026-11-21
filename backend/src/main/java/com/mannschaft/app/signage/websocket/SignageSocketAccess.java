package com.mannschaft.app.signage.websocket;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;

import java.time.Instant;
import java.time.LocalDateTime;

/** tokenと画面を1文で照合する内部projection。raw tokenは含めない。 */
public record SignageSocketAccess(Long tokenId, Long screenId, Instant expiredAt, String allowedIps) {
    /** JPAが返す既存の壁時計を、アプリ層の基準ゾーンで瞬間へ変換して保持する。 */
    public SignageSocketAccess(Long tokenId, Long screenId, LocalDateTime expiredAt, String allowedIps) {
        this(tokenId, screenId,
                expiredAt == null ? null : expiredAt.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant(), allowedIps);
    }
}
