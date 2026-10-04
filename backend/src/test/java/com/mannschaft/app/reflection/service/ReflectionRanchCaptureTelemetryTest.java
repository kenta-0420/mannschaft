package com.mannschaft.app.reflection.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 実記録境界を検証する。外部計測の故障fixtureはHTTP/認可/DBの証拠に流用しない。 */
class ReflectionRanchCaptureTelemetryTest {
    @Test void normalRegistryRetainsFixedClassificationCount() {
        try(var registry=new SimpleMeterRegistry()) {
            var telemetry=new ReflectionRanchCaptureTelemetry(registry);
            telemetry.lost(ReflectionRanchCaptureTelemetry.Reason.QUEUE_DISABLED,null);
            assertThat(telemetry.lossCount(ReflectionRanchCaptureTelemetry.Reason.QUEUE_DISABLED)).isEqualTo(1);
            assertThat(registry.get("ranch.source.capture.lost").tags("source","PERSONAL_RECALL_COMPLETE",
                    "classification","QUEUE_DISABLED").counter().count()).isEqualTo(1);
            assertThat(telemetry.telemetryFailureCount()).isZero();
        }
    }

    @Test void externalMetricsFailureNeverEscapesAndRetainsLoss() {
        // 自作Beanはmockせず、外部ライブラリだけを故障させる。
        MeterRegistry registry=mock(MeterRegistry.class);
        when(registry.counter(anyString(),any(String[].class)))
                .thenThrow(new IllegalStateException("synthetic-private-body-marker"));
        var telemetry=new ReflectionRanchCaptureTelemetry(registry);
        assertThatCode(()->telemetry.lost(ReflectionRanchCaptureTelemetry.Reason.POST_NATIVE_FAILURE,
                IllegalStateException.class)).doesNotThrowAnyException();
        assertThat(telemetry.lossCount(ReflectionRanchCaptureTelemetry.Reason.POST_NATIVE_FAILURE)).isEqualTo(1);
        assertThat(telemetry.telemetryFailureCount()).isEqualTo(1);
    }

    @Test void unknownReasonUsesFiniteClassification() {
        try(var registry=new SimpleMeterRegistry()) {
            var telemetry=new ReflectionRanchCaptureTelemetry(registry);
            assertThatCode(()->telemetry.lost(null,null)).doesNotThrowAnyException();
            assertThat(telemetry.lossCount(ReflectionRanchCaptureTelemetry.Reason.TELEMETRY_INPUT_INVALID)).isEqualTo(1);
        }
    }
}
