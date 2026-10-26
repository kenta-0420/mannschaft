package com.mannschaft.app.template.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/** 表示設定の保存がコミットされた後に監査記録するためのイベント。 */
@Getter
public class ScopeFeatureDisplayPolicyUpdatedEvent extends BaseEvent {
    public static final String AUDIT_EVENT_TYPE = "SCOPE_FEATURE_DISPLAY_POLICY_UPDATED";
    private final Long actorUserId;
    private final Long teamId;
    private final Long organizationId;
    private final String metadataJson;

    public ScopeFeatureDisplayPolicyUpdatedEvent(Long actorUserId, Long teamId, Long organizationId, String metadataJson) {
        this.actorUserId = actorUserId;
        this.teamId = teamId;
        this.organizationId = organizationId;
        this.metadataJson = metadataJson;
    }
}
