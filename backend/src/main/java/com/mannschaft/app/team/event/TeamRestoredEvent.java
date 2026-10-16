package com.mannschaft.app.team.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

import java.time.LocalDateTime;

/** チーム復元の成功コミット後に、操作者・対象チーム・元の削除日時を監査へ渡すイベント。 */
@Getter
public class TeamRestoredEvent extends BaseEvent {

    private final Long userId;
    private final Long teamId;
    private final LocalDateTime originallyDeletedAt;

    public TeamRestoredEvent(Long userId, Long teamId, LocalDateTime originallyDeletedAt) {
        super();
        this.userId = userId;
        this.teamId = teamId;
        this.originallyDeletedAt = originallyDeletedAt;
    }
}
