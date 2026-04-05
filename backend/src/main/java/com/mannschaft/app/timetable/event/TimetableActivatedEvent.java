package com.mannschaft.app.timetable.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 時間割が有効化（ACTIVE）されたときに発行されるイベント。
 */
@Getter
public class TimetableActivatedEvent extends BaseEvent {

    private final Long timetableId;
    private final Long teamId;

    public TimetableActivatedEvent(Long timetableId, Long teamId) {
        super();
        this.timetableId = timetableId;
        this.teamId = teamId;
    }
}
