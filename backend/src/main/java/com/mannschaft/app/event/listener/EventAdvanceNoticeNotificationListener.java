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
 * <h2>Codex 検分 2巡目 [P2] 是正: 組み立ての隔離粒度を受信者単位に揃える</h2>
 * <p>是正前は {@code buildRequests} が主催者・見守り者<b>全員ぶんを一括で</b>組み立てており、
 * 誰か1人（例: 主催者）の {@code getLocale} が例外を投げると、既に組み立て済みの他の受信者ぶんも
 * まとめて破棄されていた（配送 {@code sendOne} は1件ずつ隔離できているのに、組み立てだけ一括の
 * ままという退行）。本クラスは以下の2段に分ける:</p>
 * <ol>
 *   <li><b>受信者リストの解決</b>（{@link #resolveContext}）: {@code eventService.findEventOrThrow}
 *       / {@code careLinkService.getActiveWatchers} / ロケールのバルク解決など、全受信者に共通する
 *       前提情報を<b>全体で1回</b>解決する。ここが失敗したら誰にも送れないため、外側の
 *       {@code try/catch} のままでよい（規約の「配送層の1件単位独立トランザクション」は受信者ごとの
 *       組み立て・送信に適用する軸であり、受信者を横断する共通解決には適用しない）。</li>
 *   <li><b>受信者ごとの組み立て＋送信</b>（{@link #sendToOrganizer} / {@link #sendToWatcher}）:
 *       件名/本文の {@code messageSource.getMessage} 呼び出しと {@link NotificationDeliveryRunner#sendOne}
 *       を<b>1受信者ぶんずつ</b> {@code try/catch} で包む。1受信者の組み立て失敗は他の受信者への
 *       配送に影響しない。</li>
 * </ol>
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
        NotificationContext ctx;
        try {
            ctx = resolveContext(event);
        } catch (Exception e) {
            // 受信者リストの解決（全体で1回）が失敗した場合は誰にも送れないため、ここは一括で諦める。
            log.error("事前連絡通知の受信者解決に失敗しました: eventId={}, targetUserId={}, kind={}",
                    event.eventId(), event.targetUserId(), event.kind(), e);
            return;
        }

        // 受信者ごとの組み立て＋送信は1件ずつ隔離する（Codex検分2巡目[P2]是正）。
        if (ctx.organizerUserId() != null) {
            sendToOrganizer(event, ctx);
        }
        for (Long watcherId : ctx.notifiableWatcherIds()) {
            sendToWatcher(event, ctx, watcherId);
        }
    }

    /**
     * 受信者リストの解決（全体で1回）。ここが失敗したら誰にも送れないため、呼び出し元は一括で諦める。
     */
    private NotificationContext resolveContext(EventAdvanceNoticeNotificationEvent event) {
        EventEntity eventEntity = eventService.findEventOrThrow(event.eventId());
        String displayName = getUserDisplayName(event.targetUserId());

        List<Long> notifiableWatcherIds = List.of();
        Map<Long, String> watcherLocales = Map.of();
        List<Long> allWatcherIds = careLinkService.getActiveWatchers(event.targetUserId(), "RSVP");
        if (allWatcherIds.contains(event.operatorUserId())) {
            // 見守り者の locale をバルク解決（N+1 防止）。操作者自身を除いた他の見守り者が対象。
            watcherLocales = userLocaleCache.getLocales(allWatcherIds);
            notifiableWatcherIds = allWatcherIds.stream()
                    .filter(id -> !id.equals(event.operatorUserId()))
                    .toList();
        }

        return new NotificationContext(eventEntity, displayName, eventEntity.getCreatedBy(),
                notifiableWatcherIds, watcherLocales);
    }

    /** 主催者ぶんの組み立て＋送信を1件だけ隔離して行う。 */
    private void sendToOrganizer(EventAdvanceNoticeNotificationEvent event, NotificationContext ctx) {
        try {
            Locale organizerLocale = Locale.forLanguageTag(userLocaleCache.getLocale(ctx.organizerUserId()));
            String title = messageSource.getMessage(
                    titleKey(event), null, defaultTitle(event), organizerLocale);
            String body = messageSource.getMessage(
                    bodyKey(event), bodyArgs(event, ctx.displayName()), defaultBody(event, ctx.displayName()),
                    organizerLocale);
            NotificationDeliveryRequest request = new NotificationDeliveryRequest(
                    ctx.organizerUserId(),
                    type(event).name(),
                    NotificationPriority.NORMAL,
                    title, body,
                    "EVENT", ctx.eventEntity().getId(),
                    NotificationScopeType.TEAM, event.teamId(),
                    "/teams/" + event.teamId() + "/events/" + ctx.eventEntity().getId(), event.operatorUserId());
            sendOne(request);
        } catch (Exception e) {
            log.error("主催者向け事前連絡通知の組み立てに失敗しました: eventId={}, organizerUserId={}",
                    event.eventId(), ctx.organizerUserId(), e);
        }
    }

    /** 見守り者1名ぶんの組み立て＋送信を1件だけ隔離して行う。 */
    private void sendToWatcher(EventAdvanceNoticeNotificationEvent event, NotificationContext ctx, Long watcherId) {
        try {
            Locale watcherLocale = Locale.forLanguageTag(
                    ctx.watcherLocales().getOrDefault(watcherId, "ja"));
            String title = messageSource.getMessage(
                    titleKey(event), null, defaultTitle(event), watcherLocale);
            String body = messageSource.getMessage(
                    bodyKey(event), bodyArgs(event, ctx.displayName()), defaultBody(event, ctx.displayName()),
                    watcherLocale);
            NotificationDeliveryRequest request = new NotificationDeliveryRequest(
                    watcherId,
                    type(event).name(),
                    NotificationPriority.NORMAL,
                    title, body,
                    "EVENT", event.eventId(),
                    NotificationScopeType.PERSONAL, watcherId,
                    "/teams/" + event.teamId() + "/events/" + event.eventId(), event.operatorUserId());
            sendOne(request);
        } catch (Exception e) {
            log.error("見守り者向け事前連絡通知の組み立てに失敗しました: eventId={}, watcherId={}",
                    event.eventId(), watcherId, e);
        }
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

    private EventCareNotificationType type(EventAdvanceNoticeNotificationEvent event) {
        return event.kind() == EventAdvanceNoticeNotificationEvent.Kind.LATE
                ? EventCareNotificationType.EVENT_LATE_ARRIVAL_NOTICE
                : EventCareNotificationType.EVENT_ABSENCE_NOTICE;
    }

    private String titleKey(EventAdvanceNoticeNotificationEvent event) {
        return event.kind() == EventAdvanceNoticeNotificationEvent.Kind.LATE
                ? "notification.event.rsvp.lateNotice.title" : "notification.event.rsvp.absenceNotice.title";
    }

    private String defaultTitle(EventAdvanceNoticeNotificationEvent event) {
        return event.kind() == EventAdvanceNoticeNotificationEvent.Kind.LATE ? "遅刻連絡" : "欠席連絡";
    }

    private String bodyKey(EventAdvanceNoticeNotificationEvent event) {
        return event.kind() == EventAdvanceNoticeNotificationEvent.Kind.LATE
                ? "notification.event.rsvp.lateNotice.body" : "notification.event.rsvp.absenceNotice.body";
    }

    private Object[] bodyArgs(EventAdvanceNoticeNotificationEvent event, String displayName) {
        return event.kind() == EventAdvanceNoticeNotificationEvent.Kind.LATE
                ? new Object[]{displayName, event.expectedArrivalMinutesLate()}
                : new Object[]{displayName, event.absenceReason()};
    }

    private String defaultBody(EventAdvanceNoticeNotificationEvent event, String displayName) {
        return event.kind() == EventAdvanceNoticeNotificationEvent.Kind.LATE
                ? displayName + " が " + event.expectedArrivalMinutesLate() + "分遅刻予定です"
                : displayName + " が事前欠席連絡を送りました（理由: " + event.absenceReason() + "）";
    }

    /**
     * 受信者リストの解決結果（全体で1回だけ算出する共通情報）。
     *
     * @param eventEntity          イベントエンティティ（主催者取得用）
     * @param displayName          対象ユーザーの表示名（本文組み立て用・共通）
     * @param organizerUserId      主催者ユーザーID（{@code null} なら主催者通知はスキップ）
     * @param notifiableWatcherIds 通知対象の見守り者ID一覧（操作者自身を除く）
     * @param watcherLocales       見守り者の locale バルク解決結果
     */
    private record NotificationContext(
            EventEntity eventEntity,
            String displayName,
            Long organizerUserId,
            List<Long> notifiableWatcherIds,
            Map<Long, String> watcherLocales) {
    }
}
