package com.mannschaft.app.onboarding.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * ページ閲覧イベント（想定クラス）。
 * F06.5 ナレッジベース機能から発行される想定。
 */
@Getter
public class PageViewEvent extends BaseEvent {

    private final Long pageId;
    private final Long userId;
    private final String scopeType;
    private final Long scopeId;

    public PageViewEvent(Long pageId, Long userId, String scopeType, Long scopeId) {
        super();
        this.pageId = pageId;
        this.userId = userId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
    }
}
