package com.mannschaft.app.gamification.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * バッジ付与イベント。
 * ユーザーにバッジが付与されたタイミングで発行される。
 * 通知送信等の後続処理をトリガーする。
 */
@Getter
public class BadgeAwardedEvent extends BaseEvent {

    private final Long badgeId;
    private final Long userId;
    private final String scopeType;
    private final Long scopeId;

    public BadgeAwardedEvent(Long badgeId, Long userId, String scopeType, Long scopeId) {
        super();
        this.badgeId = badgeId;
        this.userId = userId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
    }
}
