package com.mannschaft.app.schedule.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * スケジュール更新イベント。通知やダッシュボード連携に使用する。
 */
@Getter
public class ScheduleUpdatedEvent extends BaseEvent {

    private final Long scheduleId;
    private final Long updatedBy;

    public ScheduleUpdatedEvent(Long scheduleId, Long updatedBy) {
        super();
        this.scheduleId = scheduleId;
        this.updatedBy = updatedBy;
    }
}
