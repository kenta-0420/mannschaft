package com.mannschaft.app.budget.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * ワークフロー却下イベント。ワークフロー機能から発行される想定。
 */
@Getter
public class WorkflowRejectedEvent extends BaseEvent {

    private final Long workflowRequestId;
    private final String sourceType;
    private final Long sourceId;
    private final Long rejectedByUserId;
    private final String reason;

    public WorkflowRejectedEvent(Long workflowRequestId, String sourceType,
                                  Long sourceId, Long rejectedByUserId, String reason) {
        super();
        this.workflowRequestId = workflowRequestId;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.rejectedByUserId = rejectedByUserId;
        this.reason = reason;
    }
}
