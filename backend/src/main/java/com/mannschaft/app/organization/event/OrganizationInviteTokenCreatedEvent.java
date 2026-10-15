package com.mannschaft.app.organization.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 組織招待トークン作成イベント。監査ログ記録等の後続処理をトリガーする。
 */
@Getter
public class OrganizationInviteTokenCreatedEvent extends BaseEvent {

    /** トークン作成者ユーザーID */
    private final Long userId;

    /** 招待先組織ID */
    private final Long organizationId;

    /** 作成されたトークンID */
    private final Long tokenId;

    public OrganizationInviteTokenCreatedEvent(Long userId, Long organizationId, Long tokenId) {
        super();
        this.userId = userId;
        this.organizationId = organizationId;
        this.tokenId = tokenId;
    }
}
