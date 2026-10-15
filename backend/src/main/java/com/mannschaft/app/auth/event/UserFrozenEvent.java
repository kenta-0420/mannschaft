package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * ユーザー凍結イベント。管理者によるアカウント凍結時に発行される。
 */
@Getter
public class UserFrozenEvent extends BaseEvent {

    private final Long adminId;
    private final Long targetUserId;
    private final String reason;

    public UserFrozenEvent(Long adminId, Long targetUserId, String reason) {
        super();
        this.adminId = adminId;
        this.targetUserId = targetUserId;
        this.reason = reason;
    }
}
