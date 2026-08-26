package com.mannschaft.app.schedule.service;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.schedule.event.ReminderNotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 予定リマインダー通知リスナー（機能55 第二陣）。
 *
 * <p>{@link ReminderNotificationEvent} を受信し、{@link NotificationHelper#notifyAllPreAuthorized}
 * 経由で IN_APP + PUSH を配信する。リマインダーバッチの {@code @Transactional} 内で同期実行され、
 * 通知作成は同一トランザクションで確定する（既存の代理出席通知と同じ作法）。</p>
 *
 * <p>通知種別は {@code SCHEDULE_REMINDER}。文言はイベントに同梱された
 * ローカライズ済みタイトル／本文を使用する（共有＝未回答者向け、個人＝所有者向け）。</p>
 *
 * <p><b>配信＝受信権 統一（関所(1)通知 / (B) レグ取りこぼし根治）</b>:
 * 本リスナーへ届く {@link ReminderNotificationEvent#getRecipientUserIds()} は、いずれの発火元でも
 * 配信母集団として事前認可済みである:
 * <ul>
 *   <li>{@link ScheduleReminderService}（共有予定）→ {@code schedule_attendances} 行由来。
 *       当該行は {@link ScheduleAttendanceService#generateAttendanceRecords} が配信母集団
 *       （ORG={@code resolveOrgDistributionUserIds} の includeSupporters トグル準拠で配下チーム展開／
 *       TEAM={@code findUserIdsByScope}）を materialize した集合であり、出欠募集通知（同
 *       {@code createNotificationPreAuthorized}）と同一母集団。</li>
 *   <li>{@link PersonalScheduleReminderService}（個人予定）→ 予定所有者本人（{@code schedule.userId}）のみ。</li>
 * </ul>
 * よって {@link NotificationHelper#notifyAllPreAuthorized} を用い canView 二重判定を通さない。
 * これにより組織スケジュールのリマインドが配下チームの直属一般メンバーへ誤 deny で届かない
 * (B) レグを根治する（出欠募集通知の {@code createNotificationPreAuthorized} 化と同形）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleReminderNotificationListener {

    /** リマインダー通知の通知種別。 */
    public static final String NOTIFICATION_TYPE = "SCHEDULE_REMINDER";

    /** 通知ソース種別（visibility ガードのソース判定にも使用）。 */
    private static final String SOURCE_TYPE = "SCHEDULE";

    private final NotificationHelper notificationHelper;

    /**
     * リマインダー通知イベントを処理する。
     *
     * @param event リマインダー通知イベント
     */
    @EventListener
    public void onReminderNotification(ReminderNotificationEvent event) {
        if (event.getRecipientUserIds() == null || event.getRecipientUserIds().isEmpty()) {
            return;
        }
        notificationHelper.notifyAllPreAuthorized(
                event.getRecipientUserIds(),
                NOTIFICATION_TYPE,
                NotificationPriority.NORMAL,
                event.getTitle(),
                event.getBody(),
                SOURCE_TYPE,
                event.getScheduleId(),
                event.getScopeType(),
                event.getScopeId(),
                event.getActionUrl(),
                null);
        log.info("予定リマインダー通知発火: scheduleId={}, 対象者数={}",
                event.getScheduleId(), event.getRecipientUserIds().size());
    }
}
