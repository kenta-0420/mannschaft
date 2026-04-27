package com.mannschaft.app.family.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.EventErrorCode;
import com.mannschaft.app.event.entity.EventCareNotificationLogEntity;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.repository.EventCareNotificationLogRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.family.CareCategory;
import com.mannschaft.app.family.EventCareNotificationType;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ケア対象者イベント通知サービス。F03.12 Phase3。
 *
 * <p>RSVP確認・チェックイン・解散・不在アラート等のトリガーで見守り者へ通知を送信する。
 * 各メソッドはケアリンクの存在チェックと冪等チェックを行った後、通知を作成・配信・ログ記録する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CareEventNotificationService {

    private final CareLinkService careLinkService;
    private final NotificationService notificationService;
    private final NotificationDispatchService dispatchService;
    private final EventCareNotificationLogRepository notificationLogRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;

    // =========================================================
    // 公開 API
    // =========================================================

    /**
     * RSVP 確認時に見守り者へ通知する。
     * EventRsvpService.submitRsvp() から呼び出す。
     *
     * @param recipientUserId ケア対象者のユーザーID
     * @param eventId         対象イベントID
     */
    @Transactional
    public void notifyRsvpConfirmed(Long recipientUserId, Long eventId) {
        if (!careLinkService.isUnderCare(recipientUserId)) return;

        List<Long> watcherIds = careLinkService.getActiveWatchers(recipientUserId, "RSVP");
        if (watcherIds.isEmpty()) return;

        EventEntity event = findEvent(eventId);
        String recipientName = getDisplayName(recipientUserId);
        CareCategory category = getCareCategory(recipientUserId);
        String eventLabel = resolveEventLabel(event);

        for (Long watcherId : watcherIds) {
            // 冪等チェック（同じイベント・ケア対象者・通知種別の組み合わせは一度のみ送信）
            if (notificationLogRepository.existsByEventIdAndCareRecipientUserIdAndWatcherUserIdAndNotificationType(
                    eventId, recipientUserId, watcherId, EventCareNotificationType.RSVP_CONFIRMED)) continue;

            String title = buildTitle(EventCareNotificationType.RSVP_CONFIRMED, category, recipientName, eventLabel);
            String body = buildBody(EventCareNotificationType.RSVP_CONFIRMED, category, recipientName, eventLabel, null);

            NotificationEntity notification = notificationService.createNotification(
                    watcherId,
                    EventCareNotificationType.RSVP_CONFIRMED.name(),
                    NotificationPriority.NORMAL,
                    title, body,
                    "EVENT", eventId,
                    NotificationScopeType.PERSONAL, watcherId,
                    "/events/" + eventId, recipientUserId);

            dispatchService.dispatch(notification);
            logNotification(eventId, recipientUserId, watcherId, category, EventCareNotificationType.RSVP_CONFIRMED, notification.getId());

            log.info("RSVP確認通知送信: eventId={}, recipientUserId={}, watcherId={}", eventId, recipientUserId, watcherId);
        }
    }

    /**
     * チェックイン時に見守り者へ通知する。
     * EventCheckinService.staffCheckin/selfCheckin から呼び出す。
     *
     * @param recipientUserId ケア対象者のユーザーID
     * @param eventId         対象イベントID
     */
    @Transactional
    public void notifyCheckin(Long recipientUserId, Long eventId) {
        if (!careLinkService.isUnderCare(recipientUserId)) return;

        List<Long> watcherIds = careLinkService.getActiveWatchers(recipientUserId, "CHECKIN");
        if (watcherIds.isEmpty()) return;

        EventEntity event = findEvent(eventId);
        String recipientName = getDisplayName(recipientUserId);
        CareCategory category = getCareCategory(recipientUserId);
        String eventLabel = resolveEventLabel(event);

        for (Long watcherId : watcherIds) {
            if (notificationLogRepository.existsByEventIdAndCareRecipientUserIdAndWatcherUserIdAndNotificationType(
                    eventId, recipientUserId, watcherId, EventCareNotificationType.CHECKIN)) continue;

            String title = buildTitle(EventCareNotificationType.CHECKIN, category, recipientName, eventLabel);
            String body = buildBody(EventCareNotificationType.CHECKIN, category, recipientName, eventLabel, null);

            NotificationEntity notification = notificationService.createNotification(
                    watcherId,
                    EventCareNotificationType.CHECKIN.name(),
                    NotificationPriority.NORMAL,
                    title, body,
                    "EVENT", eventId,
                    NotificationScopeType.PERSONAL, watcherId,
                    "/events/" + eventId, recipientUserId);

            dispatchService.dispatch(notification);
            logNotification(eventId, recipientUserId, watcherId, category, EventCareNotificationType.CHECKIN, notification.getId());

            log.info("チェックイン通知送信: eventId={}, recipientUserId={}, watcherId={}", eventId, recipientUserId, watcherId);
        }
    }

    /**
     * 解散通知時に見守り者へ通知する。
     *
     * @param recipientUserId ケア対象者のユーザーID
     * @param eventId         対象イベントID
     */
    @Transactional
    public void notifyDismissal(Long recipientUserId, Long eventId) {
        if (!careLinkService.isUnderCare(recipientUserId)) return;

        List<Long> watcherIds = careLinkService.getActiveWatchers(recipientUserId, "DISMISSAL");
        if (watcherIds.isEmpty()) return;

        EventEntity event = findEvent(eventId);
        String recipientName = getDisplayName(recipientUserId);
        CareCategory category = getCareCategory(recipientUserId);
        String eventLabel = resolveEventLabel(event);

        for (Long watcherId : watcherIds) {
            String title = buildTitle(EventCareNotificationType.DISMISSAL, category, recipientName, eventLabel);
            String body = buildBody(EventCareNotificationType.DISMISSAL, category, recipientName, eventLabel, null);

            NotificationEntity notification = notificationService.createNotification(
                    watcherId,
                    EventCareNotificationType.DISMISSAL.name(),
                    NotificationPriority.NORMAL,
                    title, body,
                    "EVENT", eventId,
                    NotificationScopeType.PERSONAL, watcherId,
                    "/events/" + eventId, recipientUserId);

            dispatchService.dispatch(notification);
            logNotification(eventId, recipientUserId, watcherId, category, EventCareNotificationType.DISMISSAL, notification.getId());

            log.info("解散通知送信: eventId={}, recipientUserId={}, watcherId={}", eventId, recipientUserId, watcherId);
        }
    }

    /**
     * 不在確認（第1段階: ソフト確認）を見守り者へ送信する。
     * Phase4 の CareAbsentAlertBatchService から呼び出す。
     *
     * @param recipientUserId ケア対象者のユーザーID
     * @param eventId         対象イベントID
     */
    @Transactional
    public void sendNoContactCheck(Long recipientUserId, Long eventId) {
        sendAlertInternal(recipientUserId, eventId, EventCareNotificationType.NO_CONTACT_CHECK, NotificationPriority.NORMAL);
    }

    /**
     * 不在アラート（第2段階: 正式アラート）を見守り者へ送信する。
     * Phase4 の CareAbsentAlertBatchService から呼び出す。
     *
     * @param recipientUserId ケア対象者のユーザーID
     * @param eventId         対象イベントID
     */
    @Transactional
    public void sendAbsentAlert(Long recipientUserId, Long eventId) {
        sendAlertInternal(recipientUserId, eventId, EventCareNotificationType.ABSENT_ALERT, NotificationPriority.HIGH);
    }

    // =========================================================
    // プライベートヘルパー
    // =========================================================

    /**
     * 不在アラートの共通処理。
     */
    private void sendAlertInternal(Long recipientUserId, Long eventId,
                                   EventCareNotificationType type, NotificationPriority priority) {
        if (!careLinkService.isUnderCare(recipientUserId)) return;

        List<Long> watcherIds = careLinkService.getActiveWatchers(recipientUserId, "ABSENT_ALERT");
        if (watcherIds.isEmpty()) return;

        EventEntity event = findEvent(eventId);
        String recipientName = getDisplayName(recipientUserId);
        CareCategory category = getCareCategory(recipientUserId);
        String eventLabel = resolveEventLabel(event);

        for (Long watcherId : watcherIds) {
            if (notificationLogRepository.existsByEventIdAndCareRecipientUserIdAndWatcherUserIdAndNotificationType(
                    eventId, recipientUserId, watcherId, type)) continue;

            String title = buildTitle(type, category, recipientName, eventLabel);
            String body = buildBody(type, category, recipientName, eventLabel, null);

            NotificationEntity notification = notificationService.createNotification(
                    watcherId,
                    type.name(),
                    priority,
                    title, body,
                    "EVENT", eventId,
                    NotificationScopeType.PERSONAL, watcherId,
                    "/events/" + eventId, recipientUserId);

            dispatchService.dispatch(notification);
            logNotification(eventId, recipientUserId, watcherId, category, type, notification.getId());

            log.info("不在アラート送信: type={}, eventId={}, recipientUserId={}, watcherId={}", type, eventId, recipientUserId, watcherId);
        }
    }

    /**
     * イベントを取得する。存在しない場合は例外をスローする。
     */
    private EventEntity findEvent(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(EventErrorCode.EVENT_NOT_FOUND));
    }

    /**
     * ユーザーの表示名を取得する。
     */
    private String getDisplayName(Long userId) {
        return userRepository.findById(userId)
                .map(UserEntity::getDisplayName)
                .orElse("（不明）");
    }

    /**
     * ユーザーのケアカテゴリを取得する。存在しない場合は GENERAL_FAMILY を返す。
     */
    private CareCategory getCareCategory(Long userId) {
        return userRepository.findById(userId)
                .map(UserEntity::getCareCategory)
                .orElse(CareCategory.GENERAL_FAMILY);
    }

    /**
     * イベントの表示ラベルを解決する。subtitle が設定されていれば使用し、なければ slug を使用する。
     */
    private String resolveEventLabel(EventEntity event) {
        String subtitle = event.getSubtitle();
        return (subtitle != null && !subtitle.isBlank()) ? subtitle : event.getSlug();
    }

    /**
     * 通知ログを記録する。
     */
    private void logNotification(Long eventId, Long recipientUserId, Long watcherUserId,
                                  CareCategory category, EventCareNotificationType type, Long notificationId) {
        EventCareNotificationLogEntity logEntry = EventCareNotificationLogEntity.builder()
                .eventId(eventId)
                .careRecipientUserId(recipientUserId)
                .watcherUserId(watcherUserId)
                .careCategory(category)
                .notificationType(type)
                .notificationId(notificationId)
                .build();
        notificationLogRepository.save(logEntry);
    }

    /**
     * 通知タイトルを生成する。
     */
    private String buildTitle(EventCareNotificationType type, CareCategory category,
                               String recipientName, String eventLabel) {
        return switch (type) {
            case RSVP_CONFIRMED           -> recipientName + "が「" + eventLabel + "」に参加予定です";
            case CHECKIN                  -> recipientName + "が「" + eventLabel + "」に到着しました";
            case CHECKOUT                 -> recipientName + "が「" + eventLabel + "」を退場しました";
            case NO_CONTACT_CHECK         -> recipientName + "がまだ到着していないようです";
            case ABSENT_ALERT             -> "⚠️ " + recipientName + "の到着が確認できていません";
            case DISMISSAL                -> recipientName + "の「" + eventLabel + "」が終了しました";
            // Phase8 §15 事前遅刻・欠席連絡
            case EVENT_LATE_ARRIVAL_NOTICE -> "遅刻連絡";
            case EVENT_ABSENCE_NOTICE      -> "欠席連絡";
            // Phase9 §16 解散通知忘れリマインド
            case EVENT_DISMISSAL_REMINDER  -> "⏰ 「" + eventLabel + "」の解散通知をまだ送信していません";
        };
    }

    /**
     * 通知本文を生成する。カテゴリ（ELDERLY等）に応じてメッセージトーンを変える。
     * extra には遅刻分数や欠席理由などの付加情報を渡す（null 可）。
     */
    private String buildBody(EventCareNotificationType type, CareCategory category,
                              String recipientName, String eventLabel,
                              @SuppressWarnings("unused") String extra) {
        return switch (type) {
            case RSVP_CONFIRMED   -> "「" + eventLabel + "」への参加が確認されました。";
            case CHECKIN          -> "「" + eventLabel + "」への到着が確認されました。";
            case CHECKOUT         -> "「" + eventLabel + "」を退場しました。";
            case NO_CONTACT_CHECK ->
                category == CareCategory.ELDERLY
                    ? recipientName + "さんがまだ到着されていないようです。ご様子を確認できますか？"
                    : recipientName + "がまだ到着していないようです。連絡はありますか？";
            case ABSENT_ALERT ->
                category == CareCategory.ELDERLY
                    ? "⚠️ " + recipientName + "さんの「" + eventLabel + "」への到着が確認できておりません。ご確認をお願いします。"
                    : "⚠️ " + recipientName + "の「" + eventLabel + "」へのチェックインが確認されていません。";
            case DISMISSAL -> "「" + eventLabel + "」が終了しました。お迎えの準備をお願いします。";
            // Phase8 §15 事前遅刻・欠席連絡（extra に付加情報が入る）
            case EVENT_LATE_ARRIVAL_NOTICE ->
                extra != null
                    ? recipientName + " が " + extra + "分遅刻予定です"
                    : recipientName + " が遅刻予定です";
            case EVENT_ABSENCE_NOTICE ->
                extra != null
                    ? recipientName + " が事前欠席連絡を送りました（理由: " + extra + "）"
                    : recipientName + " が事前欠席連絡を送りました";
            // Phase9 §16 解散通知忘れリマインド
            case EVENT_DISMISSAL_REMINDER ->
                "「" + eventLabel + "」の終了予定時刻を過ぎていますが、解散通知がまだ送信されていません。参加者・保護者に解散通知を送信してください。";
        };
    }
}
