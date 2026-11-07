package com.mannschaft.app.common.ranchsource.api;

import java.time.Duration;
import java.time.Instant;

/** 保存時計を渡さず、正の有限遅延だけをDB時計へ加算するための純粋変換。 */
public final class SourceOutboxDeliveryDelay {
    private SourceOutboxDeliveryDelay() { }

    /** 小数秒を切り上げる。負値・ゼロ・overflowを補完せず拒否する。 */
    public static int positiveCeilingSeconds(Instant from,Instant to) {
        SourceOutboxDeliveryInputs.time(from);
        SourceOutboxDeliveryInputs.time(to);
        if(!to.isAfter(from)) throw SourceOutboxDeliveryInputs.invalid();
        Duration duration=Duration.between(from,to);
        try {
            long seconds=Math.addExact(duration.getSeconds(),duration.getNano()==0?0:1);
            return Math.toIntExact(seconds);
        } catch(ArithmeticException invalid) {
            throw SourceOutboxDeliveryInputs.invalid();
        }
    }
}
