package com.mannschaft.app.schedule.service;

import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.schedule.dto.AttendanceRequest;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** pure捕捉のscope形状だけの試験。実認可/lock/HTTPの証拠へ転用しない。 */
class ScheduleRanchResponseCaptureFactoryTest {
    @Test void preservesNativeTeamAndOrganizationScope() {
        var registry = new SimpleMeterRegistry();
        try {
            var factory = new ScheduleRanchResponseCaptureFactory(
                    Clock.fixed(Instant.parse("2026-10-05T01:02:03.123456Z"), ZoneOffset.UTC),
                    new ScheduleRanchCaptureTelemetry(registry));
            for (boolean team : new boolean[] {true, false}) {
                var schedule = ScheduleEntity.builder().teamId(team ? 7L : null)
                        .organizationId(team ? null : 8L).build();
                var response = ScheduleAttendanceEntity.builder().id(10L).scheduleId(2L).userId(1L)
                        .status(AttendanceStatus.UNDECIDED).build();
                var request = new AttendanceRequest("ATTENDING", null, null);
                request.armRanchCapture();
                factory.capture(schedule, response, 1L, AttendanceStatus.ATTENDING, request);
                var payload = request.takeRanchCapture();
                assertThat(payload.scopeType().name()).isEqualTo(team ? "TEAM" : "ORGANIZATION");
                assertThat(payload.canonicalScopeId()).isEqualTo(team ? "7" : "8");
                assertThat(response.getRanchFirstSelfAt()).isEqualTo(payload.occurredAt());
                assertThat(request.takeRanchCapture()).isNull();
                assertThat(request.isRanchCaptureArmed()).isFalse();
            }
        } finally { registry.close(); }
    }
    @Test void unarmedBusinessResponseCannotBeRequalifiedLater() {
        var response = ScheduleAttendanceEntity.builder().id(10L).userId(1L)
                .status(AttendanceStatus.UNDECIDED).build();
        response.respond(AttendanceStatus.ABSENT, null);
        var registry = new SimpleMeterRegistry();
        try {
            var factory = new ScheduleRanchResponseCaptureFactory(Clock.systemUTC(), new ScheduleRanchCaptureTelemetry(registry));
            var request = new AttendanceRequest("PARTIAL", null, null);
            request.armRanchCapture();
            factory.capture(ScheduleEntity.builder().teamId(7L).build(), response, 1L, AttendanceStatus.PARTIAL, request);
            assertThat(request.takeRanchCapture()).isNull();
            assertThat(response.getRanchFirstSelfAt()).isNull();
        } finally { registry.close(); }
    }
}
