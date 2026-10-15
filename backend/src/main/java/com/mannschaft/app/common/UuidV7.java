package com.mannschaft.app.common;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * アプリ層 UUIDv7 採番ユーティリティ（RFC 9562）。
 *
 * <p>エンティティ主キーと非主キーの論理IDの双方で共通利用する。</p>
 *
 * <p>レイアウト（RFC 9562 §5.7）:
 * 上位 48 bit = Unix epoch ミリ秒 / 4 bit = version(7) / 12 bit = 乱数 /
 * 2 bit = variant(10) / 62 bit = 乱数。</p>
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    /** 現在時刻（システムUTC）に基づくUUIDv7を採番する。 */
    public static UUID generate() {
        return generate(Clock.systemUTC());
    }

    /** 指定Clockの現在時刻に基づくUUIDv7を採番する。 */
    public static UUID generate(Clock clock) {
        long unixMillis = clock.millis();

        long msb = (unixMillis & 0xFFFFFFFFFFFFL) << 16;
        msb |= 0x7000L;
        msb |= RANDOM.nextInt(1 << 12);

        long lsb = RANDOM.nextLong();
        lsb = (lsb & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;

        return new UUID(msb, lsb);
    }
}
