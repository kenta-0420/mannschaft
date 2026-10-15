package com.mannschaft.app.schedule.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 出欠回答イベント。ダッシュボードのリアルタイム更新や管理者通知に使用する。
 */
@Getter
public class AttendanceRespondedEvent extends BaseEvent {

    private final Long scheduleId;
    private final Long userId;
    private final String status;

    public AttendanceRespondedEvent(Long scheduleId, Long userId, String status) {
        super();
        this.scheduleId = scheduleId;
        this.userId = userId;
        this.status = status;
    }
}
