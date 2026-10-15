package com.mannschaft.app.onboarding.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * メンバー参加イベント（想定クラス）。
 * 既存コードに MemberJoinedEvent が存在しないため、オンボーディング自動開始用に定義。
 * 将来的にメンバーシップ機能等から発行される想定。
 */
@Getter
public class MemberJoinedEvent extends BaseEvent {

    private final Long userId;
    private final String scopeType;
    private final Long scopeId;

    public MemberJoinedEvent(Long userId, String scopeType, Long scopeId) {
        super();
        this.userId = userId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
    }
}
