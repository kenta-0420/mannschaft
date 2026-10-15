package com.mannschaft.app.schedule.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * クロスチーム招待イベント。招待先への通知に使用する。
 */
@Getter
public class CrossInviteEvent extends BaseEvent {

    private final Long sourceScheduleId;
    private final String targetType;
    private final Long targetId;
    private final Long invitedBy;
    private final String action;

    public CrossInviteEvent(Long sourceScheduleId, String targetType, Long targetId,
                            Long invitedBy, String action) {
        super();
        this.sourceScheduleId = sourceScheduleId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.invitedBy = invitedBy;
        this.action = action;
    }
}
