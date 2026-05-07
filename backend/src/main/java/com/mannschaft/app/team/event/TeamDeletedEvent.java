package com.mannschaft.app.team.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * チーム削除イベント。監査ログ記録等の後続処理をトリガーする。
 */
@Getter
public class TeamDeletedEvent extends BaseEvent {

    /** 削除者ユーザーID */
    private final Long userId;

    /** 削除されたチームID */
    private final Long teamId;

    public TeamDeletedEvent(Long userId, Long teamId) {
        super();
        this.userId = userId;
        this.teamId = teamId;
    }
}
