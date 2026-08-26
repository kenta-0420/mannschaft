package com.mannschaft.app.common;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * アプリ層 UUIDv7 採番ユーティリティ（RFC 9562）。
 *
 * <p>{@code UuidV7Entity}（Hibernate {@code @UuidGenerator(style = TIME)}）は<b>エンティティの主キー</b>を
 * 永続化時に自動採番する仕組みであり、「主キーではない論理グループ ID」
 * （例: F03.4.3 予約グループの {@code reservations.group_id}）には使えない。
 * 本ユーティリティはそうした<b>非主キーの UUIDv7</b> をアプリ層で明示採番するために提供する。</p>
 *
 * <p>レイアウト（RFC 9562 §5.7）:
 * 上位 48 bit = Unix epoch ミリ秒 / 4 bit = version(7) / 12 bit = 乱数 /
 * 2 bit = variant(10) / 62 bit = 乱数。時刻順ソート可能でインデックス効率が高く、
 * 複数ノードで独立採番できる（アーキ原則6 の意図と同じ）。</p>
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    /**
     * 現在時刻（システム UTC）に基づく UUIDv7 を採番する。
     *
     * @return UUIDv7
     */
    public static UUID generate() {
        return generate(Clock.systemUTC());
    }

    /**
     * 指定 Clock の現在時刻に基づく UUIDv7 を採番する（テストの決定性用）。
     *
     * @param clock 時刻源
     * @return UUIDv7
     */
    public static UUID generate(Clock clock) {
        long unixMillis = clock.millis();

        // MSB: [48bit timestamp][4bit version=0111][12bit rand_a]
        long msb = (unixMillis & 0xFFFFFFFFFFFFL) << 16;
        msb |= 0x7000L; // version 7
        msb |= RANDOM.nextInt(1 << 12); // rand_a 12bit

        // LSB: [2bit variant=10][62bit rand_b]
        long lsb = RANDOM.nextLong();
        lsb = (lsb & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L; // variant 10

        return new UUID(msb, lsb);
    }
}
