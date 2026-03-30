package com.mannschaft.app.incident.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * インシデントSLA超過イベント。
 * インシデントの対応期限が超過したときに発行される。
 */
@Getter
public class IncidentSlaBreachedEvent extends BaseEvent {

    /** インシデントID */
    private final Long incidentId;

    /** スコープ種別（TEAM / ORGANIZATION） */
    private final String scopeType;

    /** スコープID */
    private final Long scopeId;

    /** インシデントタイトル */
    private final String title;

    /** SLA期限 */
    private final LocalDateTime slaDeadline;

    /** 担当者ユーザーID（未アサインの場合はnull） */
    private final Long assigneeId;

    public IncidentSlaBreachedEvent(Long incidentId, String scopeType, Long scopeId,
                                    String title, LocalDateTime slaDeadline, Long assigneeId) {
        super();
        this.incidentId = incidentId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.title = title;
        this.slaDeadline = slaDeadline;
        this.assigneeId = assigneeId;
    }
}
