package com.mannschaft.app.ranch.service;

import com.mannschaft.app.ranch.EggCrackStage;
import java.time.Instant;

/** elapsedだけの表示計算。試練先行骨格。 */
public class RanchEggCalculator {
    public EggCrackStage crackStage(Instant startedAt, Instant now, long smallSeconds, long wideSeconds, long durationSeconds) {
        throw new UnsupportedOperationException("卵の境界は試練先行");
    }
    public boolean hatchReady(Instant readyAt, Instant selectedAt, Instant now) {
        throw new UnsupportedOperationException("孵化準備は試練先行");
    }
}
