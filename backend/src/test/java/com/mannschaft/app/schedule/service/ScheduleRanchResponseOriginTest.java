package com.mannschaft.app.schedule.service;

import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 起点markerの純粋境界。実認可・TX・DBの証明には使用しない。 */
class ScheduleRanchResponseOriginTest {
    private ScheduleAttendanceEntity fresh() {
        return ScheduleAttendanceEntity.builder().scheduleId(1L).userId(2L).status(AttendanceStatus.UNDECIDED).build();
    }
    @Test void proxyDoesNotConsumeFirstSelfResponse() {
        var row=fresh();row.respondProxy(AttendanceStatus.ATTENDING,"代理");
        assertThat(row.freezeRanchFirstSelfResponse(Instant.parse("2026-10-05T00:00:00Z"),2L)).isTrue();
        assertThat(row.freezeRanchFirstSelfResponse(Instant.parse("2026-10-05T01:00:00Z"),2L)).isFalse();
        assertThat(row.getRanchFirstSelfAt()).isEqualTo(Instant.parse("2026-10-05T00:00:00Z"));
    }
    @Test void unsupportedPriorSelfResponseCannotBecomeANewFirstResponse() {
        var row=fresh();row.respond(AttendanceStatus.ABSENT,null);
        assertThat(row.freezeRanchFirstSelfResponse(Instant.parse("2026-10-05T00:00:00Z"),2L)).isFalse();
        assertThat(row.getRanchFirstSelfAt()).isNull();
    }
    @Test void unknownHistoricalResponseNeverQualifiesFromCurrentStatus() {
        var row=ScheduleAttendanceEntity.builder().scheduleId(1L).userId(2L).status(AttendanceStatus.ATTENDING)
                .ranchResponseHistoryKnown(false).build();
        assertThat(row.freezeRanchFirstSelfResponse(Instant.parse("2026-10-05T00:00:00Z"),2L)).isFalse();
    }
    @Test void undecidedDoesNotConsumeFirstQualifiedResponse() {
        var row=fresh();row.respond(AttendanceStatus.UNDECIDED,null);
        assertThat(row.freezeRanchFirstSelfResponse(Instant.parse("2026-10-05T00:00:00Z"),2L)).isTrue();
    }
}
