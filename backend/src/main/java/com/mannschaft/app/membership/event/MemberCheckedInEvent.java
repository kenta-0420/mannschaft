package com.mannschaft.app.membership.event;

import com.mannschaft.app.common.event.BaseEvent;
import com.mannschaft.app.membership.CheckinType;
import lombok.Getter;

/**
 * メンバーチェックインイベント。チェックイン成功時に発行される。
 * 他機能（予約自動完了、サービス記録作成等）がリスナーで購読する拡張ポイント。
 */
@Getter
public class MemberCheckedInEvent extends BaseEvent {

    private final Long memberCardId;
    private final Long userId;
    private final Long checkinId;
    private final CheckinType checkinType;
    private final Long scopeId;
    private final String scopeType;

    public MemberCheckedInEvent(Long memberCardId, Long userId, Long checkinId,
                                 CheckinType checkinType, Long scopeId, String scopeType) {
        super();
        this.memberCardId = memberCardId;
        this.userId = userId;
        this.checkinId = checkinId;
        this.checkinType = checkinType;
        this.scopeId = scopeId;
        this.scopeType = scopeType;
    }
}
