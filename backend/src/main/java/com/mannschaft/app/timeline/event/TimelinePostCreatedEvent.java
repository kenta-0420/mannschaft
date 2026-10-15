package com.mannschaft.app.timeline.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * タイムライン投稿作成イベント。
 * 投稿が公開（PUBLISHED）されたタイミングで発行される。
 * ゲーミフィケーションポイント付与等の後続処理をトリガーする。
 */
@Getter
public class TimelinePostCreatedEvent extends BaseEvent {

    private final Long postId;
    private final Long userId;
    /** スコープ種別（TEAM / ORGANIZATION / PUBLIC / PERSONAL） */
    private final String scopeType;
    private final Long scopeId;

    public TimelinePostCreatedEvent(Long postId, Long userId, String scopeType, Long scopeId) {
        super();
        this.postId = postId;
        this.userId = userId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
    }
}
