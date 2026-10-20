package com.mannschaft.app.cms.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.LongAdder;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** ゲーム専用記録の故障も元の記事保存へ伝播させない。 */
@Component
@RequiredArgsConstructor
class BlogRanchCaptureTelemetry {
    enum Reason { CAPTURE_FAILED, UNSUPPORTED_CAPTURE, POST_NATIVE_FAILURE, QUEUE_DISABLED,
        QUEUE_REJECTED, QUEUE_CONFIGURATION_INVALID, TRANSPORT_FAILED, KEY_UNAVAILABLE }
    private static final Logger LOG=LoggerFactory.getLogger(BlogRanchCaptureTelemetry.class);
    private final MeterRegistry registry;
    private final LongAdder losses=new LongAdder();
    void lost(Reason reason) {
        losses.increment();
        try { registry.counter("ranch.source.capture.lost", "source", "BLOG_FIRST_PUBLISH",
                "classification", reason.name()).increment(); } catch (RuntimeException ignored) { }
        try { LOG.warn("ブログ報酬捕捉未受付 classification={}",reason.name()); }
        catch (RuntimeException ignored) {
            try { System.err.println("BLOG_RANCH_CAPTURE_LOGGING_FAILED"); } catch (RuntimeException ignoredAgain) { }
        }
    }
    long lossCount() { return losses.sum(); }
}
