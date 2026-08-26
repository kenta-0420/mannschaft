package com.mannschaft.app.schedule.event;

import com.mannschaft.app.common.event.BaseEvent;
import com.mannschaft.app.notification.NotificationScopeType;
import lombok.Getter;

import java.util.List;

/**
 * 予定リマインダー通知イベント（機能55 第二陣）。
 *
 * <p>共有予定／個人予定のリマインダーバッチが due 判定したリマインダーについて発火する。
 * {@link com.mannschaft.app.schedule.service.ScheduleReminderNotificationListener} が受信し、
 * {@link com.mannschaft.app.notification.service.NotificationHelper} 経由で IN_APP + PUSH を配信する。</p>
 *
 * <p>呼び出し元バッチの {@code @Transactional} 内で同期発火させ、リスナーも同一トランザクションで
 * 通知を確定させる（既存 RSVP/代理出席の通知作法と同じく PUSH 失敗は配信側フォールバックに委ねる）。</p>
 */
@Getter
public class ReminderNotificationEvent extends BaseEvent {

    /** 親予定ID。 */
    private final Long scheduleId;

    /** 通知スコープ種別（共有予定は TEAM/ORGANIZATION、個人予定は PERSONAL）。 */
    private final NotificationScopeType scopeType;

    /** 通知スコープID（teamId / organizationId / userId）。 */
    private final Long scopeId;

    /** 通知対象ユーザーID一覧。 */
    private final List<Long> recipientUserIds;

    /** 通知タイトル。 */
    private final String title;

    /** 通知本文。 */
    private final String body;

    /** 遷移先URL。 */
    private final String actionUrl;

    public ReminderNotificationEvent(Long scheduleId, NotificationScopeType scopeType, Long scopeId,
                                     List<Long> recipientUserIds, String title, String body,
                                     String actionUrl) {
        super();
        this.scheduleId = scheduleId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.recipientUserIds = recipientUserIds;
        this.title = title;
        this.body = body;
        this.actionUrl = actionUrl;
    }
}
