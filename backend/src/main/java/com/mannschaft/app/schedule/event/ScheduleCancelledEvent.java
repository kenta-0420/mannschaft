package com.mannschaft.app.schedule.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * スケジュールキャンセルイベント。出欠者への通知に使用する。
 */
@Getter
public class ScheduleCancelledEvent extends BaseEvent {

    private final Long scheduleId;
    private final Long cancelledBy;

    public ScheduleCancelledEvent(Long scheduleId, Long cancelledBy) {
        super();
        this.scheduleId = scheduleId;
        this.cancelledBy = cancelledBy;
    }
}
