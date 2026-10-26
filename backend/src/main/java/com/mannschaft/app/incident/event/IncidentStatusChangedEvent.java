package com.mannschaft.app.incident.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * インシデントステータス変更イベント。
 * インシデントのステータスが変更されたときに発行される。
 */
@Getter
public class IncidentStatusChangedEvent extends BaseEvent {

    /** インシデントID */
    private final Long incidentId;

    /** スコープ種別（TEAM / ORGANIZATION） */
    private final String scopeType;

    /** スコープID */
    private final Long scopeId;

    /** 変更前ステータス */
    private final String oldStatus;

    /** 変更後ステータス */
    private final String newStatus;

    /** 変更者ユーザーID */
    private final Long changedBy;

    public IncidentStatusChangedEvent(Long incidentId, String scopeType, Long scopeId,
                                      String oldStatus, String newStatus, Long changedBy) {
        super();
        this.incidentId = incidentId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.changedBy = changedBy;
    }
}
