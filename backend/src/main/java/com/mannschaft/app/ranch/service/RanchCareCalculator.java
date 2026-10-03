package com.mannschaft.app.ranch.service;

import java.time.Instant;
import java.time.LocalDate;

/** 無料careの週境界と量を計算する。試練先行の未実装骨格。 */
public class RanchCareCalculator {
    public LocalDate weekStartsOn(Instant now) {
        throw new UnsupportedOperationException("週境界は試練先行");
    }
    public long gainedXp(long amount, long cap, long awarded) {
        throw new UnsupportedOperationException("無料care量は試練先行");
    }
}
