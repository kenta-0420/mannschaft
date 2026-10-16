package com.mannschaft.app.ranch.service;

import com.mannschaft.app.ranch.EggCrackStage;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** 卵の経過時間から表示段階と孵化可能状態を計算する。 */
public class RanchEggCalculator {
    public EggCrackStage crackStage(Instant startedAt, Instant now, long smallSeconds, long wideSeconds, long durationSeconds) {
        Objects.requireNonNull(startedAt, "開始時刻は必須です");
        Objects.requireNonNull(now, "現在時刻は必須です");
        if (smallSeconds <= 0 || wideSeconds <= smallSeconds || durationSeconds <= wideSeconds) {
            throw new IllegalArgumentException("卵の段階時刻が不正です");
        }
        Duration elapsed = Duration.between(startedAt, now);
        if (elapsed.compareTo(Duration.ofSeconds(durationSeconds)) >= 0) {
            return EggCrackStage.READY;
        }
        if (elapsed.compareTo(Duration.ofSeconds(wideSeconds)) >= 0) {
            return EggCrackStage.WIDE_CRACK;
        }
        if (elapsed.compareTo(Duration.ofSeconds(smallSeconds)) >= 0) {
            return EggCrackStage.SMALL_CRACK;
        }
        return EggCrackStage.INTACT;
    }

    public boolean hatchReady(Instant readyAt, Instant selectedAt, Instant now) {
        Objects.requireNonNull(readyAt, "孵化可能時刻は必須です");
        Objects.requireNonNull(now, "現在時刻は必須です");
        return selectedAt != null && !selectedAt.isAfter(now) && !now.isBefore(readyAt);
    }
}
