package com.mannschaft.app.auth.event;

import com.mannschaft.app.auth.entity.UserEntity.UserStatus;
import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * ユーザーステータス変更イベント（F14.1 Phase 13-β）。
 * DECEASED・RELOCATED などのライフイベント発生時に発行し、
 * 代理入力同意書の自動失効などの後続処理をトリガーする。
 */
@Getter
public class UserStatusChangedEvent extends BaseEvent {

    /** イベント発行元オブジェクト（Spring ApplicationEvent 互換のため保持）。 */
    private final Object source;

    /** ステータスが変更されたユーザーのID。 */
    private final Long userId;

    /** 変更前のステータス。 */
    private final UserStatus oldStatus;

    /** 変更後のステータス。 */
    private final UserStatus newStatus;

    public UserStatusChangedEvent(Object source, Long userId, UserStatus oldStatus, UserStatus newStatus) {
        super();
        this.source = source;
        this.userId = userId;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
    }
}
