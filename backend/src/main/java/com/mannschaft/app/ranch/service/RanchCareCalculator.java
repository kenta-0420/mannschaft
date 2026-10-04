package com.mannschaft.app.ranch.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;

/** 無料 care は UTC 月曜の週枠を使い、同日の利用回数で成長を制限しない。 */
public class RanchCareCalculator {
    public LocalDate weekStartsOn(Instant now) {
        return Objects.requireNonNull(now, "時刻は必須です").atOffset(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public long gainedXp(long amount, long cap, long awarded) {
        if (amount <= 0 || cap <= 0 || awarded < 0 || awarded > cap) {
            throw new IllegalArgumentException("care 規則または週枠が不正です");
        }
        // amount + awarded を先に足さず、BIGINT 最大値でも残枠から安全に求める。
        return Math.min(amount, cap - awarded);
    }
}
