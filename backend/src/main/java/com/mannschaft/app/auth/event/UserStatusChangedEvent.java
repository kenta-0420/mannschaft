package com.mannschaft.app.auth.event;

import com.mannschaft.app.auth.entity.UserEntity.UserStatus;
import org.springframework.context.ApplicationEvent;

/**
 * ユーザーステータスが変更されたときに発行されるイベント（F14.1）。
 * ProxyConsentLifeEventJob がこのイベントを受信して同意書を自動失効させる。
 */
public class UserStatusChangedEvent extends ApplicationEvent {

    private final Long userId;
    private final UserStatus oldStatus;
    private final UserStatus newStatus;

    public UserStatusChangedEvent(Object source, Long userId, UserStatus oldStatus, UserStatus newStatus) {
        super(source);
        this.userId = userId;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
    }

    public Long getUserId() { return userId; }
    public UserStatus getOldStatus() { return oldStatus; }
    public UserStatus getNewStatus() { return newStatus; }
}
