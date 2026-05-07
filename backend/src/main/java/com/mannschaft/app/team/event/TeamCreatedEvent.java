package com.mannschaft.app.team.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * チーム作成イベント。監査ログ記録等の後続処理をトリガーする。
 */
@Getter
public class TeamCreatedEvent extends BaseEvent {

    /** 作成者ユーザーID */
    private final Long userId;

    /** 作成されたチームID */
    private final Long teamId;

    /** チーム名 */
    private final String teamName;

    public TeamCreatedEvent(Long userId, Long teamId, String teamName) {
        super();
        this.userId = userId;
        this.teamId = teamId;
        this.teamName = teamName;
    }
}
