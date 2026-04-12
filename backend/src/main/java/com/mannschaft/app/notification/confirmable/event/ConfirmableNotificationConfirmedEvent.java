package com.mannschaft.app.notification.confirmable.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * F04.9 確認通知確認済みイベント。
 *
 * <p>受信者が確認通知を確認した際に発行される。
 * リスナー側（第三陣 Controller/DTO 部隊）が {@code @TransactionalEventListener(phase=AFTER_COMMIT)}
 * + {@code @Async("event-pool")} で受け取り、F04.3 {@code NotificationHelper.notify()} 経由で
 * 送信者への完了通知などを行う。</p>
 */
@Getter
public class ConfirmableNotificationConfirmedEvent extends BaseEvent {

    /** 確認された確認通知ID */
    private final Long confirmableNotificationId;

    /** 確認を行ったユーザーID */
    private final Long userId;

    /** 確認日時 */
    private final LocalDateTime confirmedAt;

    public ConfirmableNotificationConfirmedEvent(
            Long confirmableNotificationId,
            Long userId,
            LocalDateTime confirmedAt) {
        super();
        this.confirmableNotificationId = confirmableNotificationId;
        this.userId = userId;
        this.confirmedAt = confirmedAt;
    }
}
