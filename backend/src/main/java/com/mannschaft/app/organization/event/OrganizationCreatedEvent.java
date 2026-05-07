package com.mannschaft.app.organization.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 組織作成イベント。監査ログ記録等の後続処理をトリガーする。
 */
@Getter
public class OrganizationCreatedEvent extends BaseEvent {

    /** 作成者ユーザーID */
    private final Long userId;

    /** 作成された組織ID */
    private final Long organizationId;

    /** 組織名 */
    private final String organizationName;

    public OrganizationCreatedEvent(Long userId, Long organizationId, String organizationName) {
        super();
        this.userId = userId;
        this.organizationId = organizationId;
        this.organizationName = organizationName;
    }
}
