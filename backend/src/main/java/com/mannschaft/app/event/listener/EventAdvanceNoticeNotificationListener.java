package com.mannschaft.app.event.listener;

import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.event.EventAdvanceNoticeNotificationEvent;
import com.mannschaft.app.event.service.EventService;
import com.mannschaft.app.family.EventCareNotificationType;
import com.mannschaft.app.family.service.CareLinkService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * F03.12 事前遅刻・欠席連絡の通知配送リスナー（Issue #2834 / CMP-056 型確立PR）。
 *
 * <p>{@code EventRsvpService} の業務トランザクションが commit された後（{@code AFTER_COMMIT}）に
 * 非同期（{@code event-pool}）で発火する。<b>通知の文面組み立て（主催者・見守り者の解決・ロケール
 * 解決・件名/本文組み立て）も本リスナーの責務</b>（Codex 検分 [P2] 是正・
 * {@link EventAdvanceNoticeNotificationEvent} javadoc 参照）。業務行（rsvp の save）は
 * AFTER_COMMIT の時点で既にコミット済みのため、ここで DB 読み取りを行っても業務トランザクションを
 * 巻き込まない。受信者（主催者 + 見守り者）ごとに {@link NotificationDeliveryRunner#sendOne}
 * （{@code REQUIRES_NEW}）を<b>1件ずつ</b>呼ぶ（AC-7）。</p>
 *
 * <h2>D-5: 越境アクセスは Repository ではなく Service 経由</h2>
 * <p>{@code auth} ドメインへの越境は {@code UserRepository} を直接 DI せず
 * {@code UserService#getDisplayName}（Service 経由）を使う（CLAUDE.md ドメイン境界の原則・
 * {@code CrossDomainRepositoryDependencyArchTest} D-5）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventAdvanceNoticeNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final EventService eventService;
    /** Issue #2834 / CMP-056 検分対応（D-5）: 越境アクセスは Repository 直接ではなく Service 経由。 */
    private final UserService userService;
    private final CareLinkService careLinkService;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEventAdvanceNoticeNotification(EventAdvanceNoticeNotificationEvent event) {
        List<NotificationDeliveryRequest> requests;
        try {
            requests = buildRequests(event);
        } catch (Exception e) {
            log.error("事前連絡通知の組み立てに失敗しました: eventId={}, targetUserId={}, kind={}",
                    event.eventId(), event.targetUserId(), event.kind(), e);
            return;
        }
        for (NotificationDeliveryRequest request : requests) {
            sendOne(request);
        }
    }

    /**
     * 通知配送要求の一覧（主催者 + 見守り者）を組み立てる（Codex検分[P2]是正: 業務TX外・AFTER_COMMIT後）。
     */
    private List<NotificationDeliveryRequest> buildRequests(EventAdvanceNoticeNotificationEvent event) {
        java.util.ArrayList<NotificationDeliveryRequest> requests = new java.util.ArrayList<>();

        EventEntity eventEntity = eventService.findEventOrThrow(event.eventId());
        EventCareNotificationType type = event.kind() == EventAdvanceNoticeNotificationEvent.Kind.LATE
                ? EventCareNotificationType.EVENT_LATE_ARRIVAL_NOTICE
                : EventCareNotificationType.EVENT_ABSENCE_NOTICE;
        String titleKey = event.kind() == EventAdvanceNoticeNotificationEvent.Kind.LATE
                ? "notification.event.rsvp.lateNotice.title" : "notification.event.rsvp.absenceNotice.title";
        String defaultTitle = event.kind() == EventAdvanceNoticeNotificationEvent.Kind.LATE ? "遅刻連絡" : "欠席連絡";
        String bodyKey = event.kind() == EventAdvanceNoticeNotificationEvent.Kind.LATE
                ? "notification.event.rsvp.lateNotice.body" : "notification.event.rsvp.absenceNotice.body";
        String displayName = getUserDisplayName(event.targetUserId());
        Object[] bodyArgs = event.kind() == EventAdvanceNoticeNotificationEvent.Kind.LATE
                ? new Object[]{displayName, event.expectedArrivalMinutesLate()}
                : new Object[]{displayName, event.absenceReason()};
        String defaultBody = event.kind() == EventAdvanceNoticeNotificationEvent.Kind.LATE
                ? displayName + " が " + event.expectedArrivalMinutesLate() + "分遅刻予定です"
                : displayName + " が事前欠席連絡を送りました（理由: " + event.absenceReason() + "）";

        // 主催者へ通知
        Long organizerUserId = eventEntity.getCreatedBy();
        if (organizerUserId != null) {
            Locale organizerLocale = Locale.forLanguageTag(userLocaleCache.getLocale(organizerUserId));
            String title = messageSource.getMessage(titleKey, null, defaultTitle, organizerLocale);
            String body = messageSource.getMessage(bodyKey, bodyArgs, defaultBody, organizerLocale);
            requests.add(new NotificationDeliveryRequest(
                    organizerUserId,
                    type.name(),
                    NotificationPriority.NORMAL,
                    title, body,
                    "EVENT", eventEntity.getId(),
                    NotificationScopeType.TEAM, event.teamId(),
                    "/teams/" + event.teamId() + "/events/" + eventEntity.getId(), event.operatorUserId()));
        }

        // 操作者がケア対象者の見守り者かどうかを確認し、そうであれば他の見守り者にも通知する（代理申告の共有）
        List<Long> allWatcherIds = careLinkService.getActiveWatchers(event.targetUserId(), "RSVP");
        if (allWatcherIds.contains(event.operatorUserId())) {
            Map<Long, String> watcherLocales = userLocaleCache.getLocales(allWatcherIds);
            for (Long watcherId : allWatcherIds) {
                if (watcherId.equals(event.operatorUserId())) continue;

                Locale watcherLocale = Locale.forLanguageTag(watcherLocales.getOrDefault(watcherId, "ja"));
                String title = messageSource.getMessage(titleKey, null, defaultTitle, watcherLocale);
                String body = messageSource.getMessage(bodyKey, bodyArgs, defaultBody, watcherLocale);

                requests.add(new NotificationDeliveryRequest(
                        watcherId,
                        type.name(),
                        NotificationPriority.NORMAL,
                        title, body,
                        "EVENT", event.eventId(),
                        NotificationScopeType.PERSONAL, watcherId,
                        "/teams/" + event.teamId() + "/events/" + event.eventId(), event.operatorUserId()));
            }
        }

        return requests;
    }

    private String getUserDisplayName(Long userId) {
        return userService.getDisplayName(userId);
    }

    private void sendOne(NotificationDeliveryRequest request) {
        try {
            NotificationEntity created = notificationDeliveryRunner.sendOne(request);
            if (created == null) {
                log.warn("事前連絡通知が visibility deny によりスキップされました: "
                                + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}",
                        request.recipientUserId(), request.notificationType(),
                        request.sourceType(), request.sourceId());
            }
        } catch (Exception e) {
            log.error("事前連絡通知の配送に失敗しました: "
                            + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}, actorId={}",
                    request.recipientUserId(), request.notificationType(),
                    request.sourceType(), request.sourceId(), request.actorId(), e);
        }
    }
}
