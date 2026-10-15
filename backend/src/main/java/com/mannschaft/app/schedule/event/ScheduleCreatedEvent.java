package com.mannschaft.app.schedule.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * スケジュール作成イベント。出欠レコード生成や通知に使用する。
 */
@Getter
public class ScheduleCreatedEvent extends BaseEvent {

    private final Long scheduleId;
    private final String scopeType;
    private final Long scopeId;
    private final Long createdBy;
    private final boolean attendanceRequired;

    public ScheduleCreatedEvent(Long scheduleId, String scopeType, Long scopeId,
                                Long createdBy, boolean attendanceRequired) {
        super();
        this.scheduleId = scheduleId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.createdBy = createdBy;
        this.attendanceRequired = attendanceRequired;
    }
}
