package com.mannschaft.app.shift.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * シフトアーカイブイベント。ARCHIVED 遷移時にトランザクション内で発行される。
 * Phase 4-γ: ShiftArchivedToTodoCancelListener が購読し、紐づく Todo を自動 CANCELLED にする。
 */
@Getter
public class ShiftArchivedEvent extends BaseEvent {

    private final Long scheduleId;
    private final Long teamId;
    /** バッチ実行による自動アーカイブの場合は null。 */
    private final Long archivedByUserId;

    public ShiftArchivedEvent(Long scheduleId, Long teamId, Long archivedByUserId) {
        super();
        this.scheduleId = scheduleId;
        this.teamId = teamId;
        this.archivedByUserId = archivedByUserId;
    }
}
