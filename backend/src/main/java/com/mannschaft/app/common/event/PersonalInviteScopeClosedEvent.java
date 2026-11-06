package com.mannschaft.app.common.event;

import lombok.Getter;

/** 親domainの閉鎖TXが確定した世代を個人招待へ通知する。加盟ACTIVE/PENDINGの片付けとは別契約。 */
@Getter
public class PersonalInviteScopeClosedEvent extends BaseEvent {
    private final String scopeType;
    private final Long scopeId;
    private final long closureGeneration;

    public PersonalInviteScopeClosedEvent(String scopeType, Long scopeId, long closureGeneration) {
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.closureGeneration = closureGeneration;
    }
}
