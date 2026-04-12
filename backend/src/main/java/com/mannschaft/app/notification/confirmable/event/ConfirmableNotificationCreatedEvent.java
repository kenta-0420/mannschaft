package com.mannschaft.app.notification.confirmable.event;

import com.mannschaft.app.common.event.BaseEvent;
import com.mannschaft.app.membership.ScopeType;
import lombok.Getter;

import java.util.List;

/**
 * F04.9 確認通知作成イベント。
 *
 * <p>確認通知が送信された際に発行される。
 * リスナー側（第三陣 Controller/DTO 部隊）が {@code @TransactionalEventListener(phase=AFTER_COMMIT)}
 * + {@code @Async("event-pool")} で受け取り、メール/LINE等の外部配信を行う。</p>
 */
@Getter
public class ConfirmableNotificationCreatedEvent extends BaseEvent {

    /** 作成された確認通知ID */
    private final Long confirmableNotificationId;

    /** スコープ種別（TEAM / ORGANIZATION） */
    private final ScopeType scopeType;

    /** スコープID（チームIDまたは組織ID） */
    private final Long scopeId;

    /** 受信者ユーザーIDリスト */
    private final List<Long> recipientUserIds;

    public ConfirmableNotificationCreatedEvent(
            Long confirmableNotificationId,
            ScopeType scopeType,
            Long scopeId,
            List<Long> recipientUserIds) {
        super();
        this.confirmableNotificationId = confirmableNotificationId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.recipientUserIds = List.copyOf(recipientUserIds);
    }
}
