package com.mannschaft.app.errorreport;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * F10.6 Phase 10-δ — severity に基づく SLA 対応期限ポリシー。
 * CRITICAL=1時間 / HIGH=24時間 / MEDIUM=1週間 / LOW=期限なし。
 */
public final class SlaPolicy {

    private SlaPolicy() {}

    public static Optional<Duration> durationFor(ErrorReportSeverity severity) {
        return switch (severity) {
            case CRITICAL -> Optional.of(Duration.ofHours(1));
            case HIGH     -> Optional.of(Duration.ofHours(24));
            case MEDIUM   -> Optional.of(Duration.ofDays(7));
            case LOW      -> Optional.empty();
        };
    }

    /** severity と基準時刻から SLA 期限を計算する。LOW は null を返す。 */
    public static LocalDateTime calcDueAt(ErrorReportSeverity severity, LocalDateTime baseTime) {
        return durationFor(severity).map(baseTime::plus).orElse(null);
    }
}
