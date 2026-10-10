package com.mannschaft.app.team;

import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;

import java.time.LocalDateTime;

/** CMP-260902-0059 の復元後掃除契約で使う最小のチーム予定フィクスチャ。 */
final class TeamRestoreTestFixture {
    private TeamRestoreTestFixture() { }

    static ScheduleEntity schedule(Long teamId, Long creatorId, LocalDateTime startAt) {
        return ScheduleEntity.builder()
                .teamId(teamId)
                .createdBy(creatorId)
                .title("復元後掃除の契約予定")
                .startAt(startAt)
                .endAt(startAt.plusHours(1))
                .eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .build();
    }
}
