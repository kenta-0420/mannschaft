package com.mannschaft.app.common.ranchsource.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 公開技術命令の固定入力検証のみ。DB・設定取得・TX制御を持たない。 */
final class SourceOutboxDeliveryInputs {
    private static final Instant MIN_DB_TIME=Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_DB_TIME=Instant.parse("9999-12-31T23:59:59.999999Z");
    private SourceOutboxDeliveryInputs() { }
    static void time(Instant time) {
        if(time==null || !time.equals(time.truncatedTo(ChronoUnit.MICROS))
                || time.isBefore(MIN_DB_TIME) || time.isAfter(MAX_DB_TIME)) throw invalid();
    }
    static void command(UUID eventId,UUID token,Instant time) {
        time(time);
        if(eventId==null || token==null) throw invalid();
    }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("源配送命令の定義が不正です"); }
}
