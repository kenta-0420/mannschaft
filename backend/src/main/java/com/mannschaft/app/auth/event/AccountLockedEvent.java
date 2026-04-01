package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * アカウントロックイベント。ブルートフォース攻撃検知によるロック発動時に発行される。
 */
@Getter
public class AccountLockedEvent extends BaseEvent {

    private final Long userId;
    private final String reason;
    private final LocalDateTime unlockAt;

    public AccountLockedEvent(Long userId, String reason, LocalDateTime unlockAt) {
        super();
        this.userId = userId;
        this.reason = reason;
        this.unlockAt = unlockAt;
    }
}
