package com.mannschaft.app.organization.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 組織削除イベント。監査ログ記録等の後続処理をトリガーする。
 */
@Getter
public class OrganizationDeletedEvent extends BaseEvent {

    /** 削除者ユーザーID */
    private final Long userId;

    /** 削除された組織ID */
    private final Long organizationId;

    public OrganizationDeletedEvent(Long userId, Long organizationId) {
        super();
        this.userId = userId;
        this.organizationId = organizationId;
    }
}
