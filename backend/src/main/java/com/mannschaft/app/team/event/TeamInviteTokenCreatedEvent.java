package com.mannschaft.app.team.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * チーム招待トークン作成イベント。監査ログ記録等の後続処理をトリガーする。
 */
@Getter
public class TeamInviteTokenCreatedEvent extends BaseEvent {

    /** トークン作成者ユーザーID */
    private final Long userId;

    /** 招待先チームID */
    private final Long teamId;

    /** 作成されたトークンID */
    private final Long tokenId;

    public TeamInviteTokenCreatedEvent(Long userId, Long teamId, Long tokenId) {
        super();
        this.userId = userId;
        this.teamId = teamId;
        this.tokenId = tokenId;
    }
}
