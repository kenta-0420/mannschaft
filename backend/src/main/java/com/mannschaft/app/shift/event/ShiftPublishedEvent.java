package com.mannschaft.app.shift.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * シフト公開イベント。PUBLISHED 遷移時にトランザクション内で発行される。
 * TODO: Google Calendar リスナーは将来追加
 * TODO: TodoListener は Phase 4-α で追加
 */
@Getter
public class ShiftPublishedEvent extends BaseEvent {

    private final Long scheduleId;
    private final Long teamId;
    private final Long triggeredByUserId;

    public ShiftPublishedEvent(Long scheduleId, Long teamId, Long triggeredByUserId) {
        super();
        this.scheduleId = scheduleId;
        this.teamId = teamId;
        this.triggeredByUserId = triggeredByUserId;
    }
}
