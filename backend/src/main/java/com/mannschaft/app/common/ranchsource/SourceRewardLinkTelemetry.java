package com.mannschaft.app.common.ranchsource;

import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 源リンクの既知停止を固定分類だけで記録する。本文・ID・例外messageは渡さない。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SourceRewardLinkTelemetry {
    private final MeterRegistry meters;
    public void unavailable(RanchRewardSourceType source) {
        try { meters.counter("ranch.source.link.unavailable", "source", source.name()).increment(); }
        catch (RuntimeException ignored) { log.warn("SOURCE_LINK_TELEMETRY_FAILED"); }
    }
}
