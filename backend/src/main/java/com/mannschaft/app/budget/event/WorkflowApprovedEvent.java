package com.mannschaft.app.budget.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * ワークフロー承認イベント。ワークフロー機能から発行される想定。
 */
@Getter
public class WorkflowApprovedEvent extends BaseEvent {

    private final Long workflowRequestId;
    private final String sourceType;
    private final Long sourceId;
    private final Long approvedByUserId;

    public WorkflowApprovedEvent(Long workflowRequestId, String sourceType,
                                  Long sourceId, Long approvedByUserId) {
        super();
        this.workflowRequestId = workflowRequestId;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.approvedByUserId = approvedByUserId;
    }
}
