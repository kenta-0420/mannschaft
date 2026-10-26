package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * アカウントロック解除イベント。管理者による手動ロック解除時に発行される。
 */
@Getter
public class AccountUnlockedEvent extends BaseEvent {

    private final Long adminId;
    private final Long targetUserId;

    public AccountUnlockedEvent(Long adminId, Long targetUserId) {
        super();
        this.adminId = adminId;
        this.targetUserId = targetUserId;
    }
}
