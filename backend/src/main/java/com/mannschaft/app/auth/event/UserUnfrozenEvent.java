package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * ユーザー凍結解除イベント。管理者によるアカウント凍結解除時に発行される。
 */
@Getter
public class UserUnfrozenEvent extends BaseEvent {

    private final Long adminId;
    private final Long targetUserId;

    public UserUnfrozenEvent(Long adminId, Long targetUserId) {
        super();
        this.adminId = adminId;
        this.targetUserId = targetUserId;
    }
}
