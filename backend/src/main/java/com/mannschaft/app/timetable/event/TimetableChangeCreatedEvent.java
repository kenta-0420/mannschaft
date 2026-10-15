package com.mannschaft.app.timetable.event;

import com.mannschaft.app.common.event.BaseEvent;
import com.mannschaft.app.timetable.TimetableChangeType;
import lombok.Getter;

import java.time.LocalDate;

/**
 * 臨時変更が作成・更新されたときに発行されるイベント。
 */
@Getter
public class TimetableChangeCreatedEvent extends BaseEvent {

    private final Long changeId;
    private final Long timetableId;
    private final Long teamId;
    private final TimetableChangeType changeType;
    private final LocalDate targetDate;
    private final boolean notifyMembers;
    private final boolean createSchedule;

    public TimetableChangeCreatedEvent(Long changeId, Long timetableId, Long teamId,
                                       TimetableChangeType changeType, LocalDate targetDate,
                                       boolean notifyMembers, boolean createSchedule) {
        super();
        this.changeId = changeId;
        this.timetableId = timetableId;
        this.teamId = teamId;
        this.changeType = changeType;
        this.targetDate = targetDate;
        this.notifyMembers = notifyMembers;
        this.createSchedule = createSchedule;
    }
}
