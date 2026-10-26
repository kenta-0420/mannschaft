package com.mannschaft.app.incident.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * インシデント報告イベント。
 * 新規インシデントが報告されたときに発行される。
 */
@Getter
public class IncidentReportedEvent extends BaseEvent {

    /** インシデントID */
    private final Long incidentId;

    /** スコープ種別（TEAM / ORGANIZATION） */
    private final String scopeType;

    /** スコープID */
    private final Long scopeId;

    /** インシデントタイトル */
    private final String title;

    /** 優先度文字列 */
    private final String priority;

    /** 報告者ユーザーID */
    private final Long reportedBy;

    public IncidentReportedEvent(Long incidentId, String scopeType, Long scopeId,
                                 String title, String priority, Long reportedBy) {
        super();
        this.incidentId = incidentId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.title = title;
        this.priority = priority;
        this.reportedBy = reportedBy;
    }
}
