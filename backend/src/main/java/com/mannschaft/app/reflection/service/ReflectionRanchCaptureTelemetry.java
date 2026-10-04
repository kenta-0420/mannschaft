package com.mannschaft.app.reflection.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.concurrent.atomic.LongAdder;

/** ゲーム専用の計測/ログ障害を本体へ伝播させず、固定分類の喪失数を保持する。 */
@Slf4j
@Component
public class ReflectionRanchCaptureTelemetry {
    public enum Reason { CAPTURE_FAILED,POST_NATIVE_FAILURE,QUEUE_DISABLED,QUEUE_REJECTED,TRANSPORT_FAILED,QUEUE_CONFIGURATION_INVALID,TELEMETRY_INPUT_INVALID }
    private final MeterRegistry metrics;
    private final EnumMap<Reason,LongAdder> losses=new EnumMap<>(Reason.class);
    private final LongAdder telemetryFailures=new LongAdder();

    public ReflectionRanchCaptureTelemetry(MeterRegistry metrics) {
        this.metrics=metrics;
        for(var reason:Reason.values()) losses.put(reason,new LongAdder());
    }

    /** 例外message/causeや元本文を受け取らない。インフラのRuntimeExceptionは外へ投げない。 */
    public void lost(Reason reason,Class<?> exceptionClass) {
        reason=reason==null?Reason.TELEMETRY_INPUT_INVALID:reason;
        losses.get(reason).increment();
        try {
            metrics.counter("ranch.source.capture.lost","source","PERSONAL_RECALL_COMPLETE",
                    "classification",reason.name()).increment();
        }catch(RuntimeException metricFailure) {
            telemetryFailures.increment();
            warn("TELEMETRY_FAILED",metricFailure.getClass());
        }
        if(reason!=Reason.QUEUE_DISABLED) warn(reason.name(),exceptionClass);
    }

    /** 外部計測が壊れても内部の固定分類counterへ喪失を残す。個人情報は保持しない。 */
    public long lossCount(Reason reason) { return losses.get(reason).sum(); }
    public long telemetryFailureCount() { return telemetryFailures.sum(); }

    private void warn(String classification,Class<?> exceptionClass) {
        try {
            log.warn("想起配送の記録: classification={} exceptionClass={}",classification,
                    exceptionClass==null?"NONE":exceptionClass.getSimpleName());
        }catch(RuntimeException loggingFailure) {
            telemetryFailures.increment();
            // logger自体が壊れた場合も固定文だけを最後の出力先へ渡し、本体へ投げない。
            try { System.err.println("想起配送の記録失敗: classification=LOGGING_FAILED"); }
            catch(RuntimeException fallbackFailure) { telemetryFailures.increment(); }
        }
    }
}
