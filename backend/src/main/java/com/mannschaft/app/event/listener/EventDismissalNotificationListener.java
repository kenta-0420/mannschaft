package com.mannschaft.app.event.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.event.EventDismissalNotificationEvent;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.family.service.CareEventNotificationService;
import com.mannschaft.app.family.service.CareLinkService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * イベント解散通知の配送リスナー（Issue #2834 / CMP-056 第1群ロットB）。
 *
 * <p>{@code EventDismissalService#sendDismissalNotification} の業務トランザクション
 * （{@code dismissal_notification_sent_at} の記録）が commit された後（{@code AFTER_COMMIT}）に
 * 非同期（{@code event-pool}）で発火する。<b>複数受信者</b>の金型として
 * {@link EventAdvanceNoticeNotificationListener} と同型（受信者リストの解決は全体で1回・外側 try、
 * 受信者ごとに組み立て＋配送を内側 try で隔離）。</p>
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code sendDismissalNotification} の {@code @Transactional} 内で参加者をループし、
 * {@code notificationService.createNotification}（既定の {@code REQUIRED} 伝播）を呼んでいた。
 * 通知側の DB 例外は業務トランザクションに rollback-only を立てるため、<b>「解散通知済み」の記録
 * （{@code recordDismissal}）ごと巻き戻り</b>、主催者から見ると解散通知が送れていないのに
 * 記録だけが消える状態になっていた。見守り者通知側の
 * {@code try/catch} も同様に rollback-only を救えていなかった。</p>
 *
 * <h2>見守り者通知も本リスナーへ移す</h2>
 * <p>{@code CareEventNotificationService}（CMP-056 の対象外クラス。通知そのものが業務目的のため
 * 一括適用しない）は<b>変更していない</b>。変えたのは<b>呼び出し位置</b>だけで、業務トランザクションの
 * 内側から本リスナー（業務TX外）へ移した。これによりケア通知の失敗が解散記録を巻き戻さなくなる。
 * ケア対象者1人ぶんの失敗が他を巻き添えにしないよう、こちらもループ内 {@code try/catch} で隔離する。</p>
 *
 * <h2>配信面の等価性</h2>
 * <p>是正前も {@code createNotification} + {@code dispatchService.dispatch} だったため、
 * {@link NotificationDeliveryRunner#sendOne}（= create + dispatch）への置換で Push/WebSocket の
 * 有無は変わらない（ロットA の回覧・招待リンクとは異なり、新たな配信は付かない）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventDismissalNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final EventRepository eventRepository;
    private final CareLinkService careLinkService;
    private final CareEventNotificationService careEventNotificationService;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "予定・行事は棚卸し台帳で beta=コア・gate_key 未発行の常時提供機能であり、解散通知だけを止める停止条件が存在しないため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEventDismissalNotification(EventDismissalNotificationEvent event) {
        List<Long> targetUserIds = event.targetUserIds();
        if (targetUserIds == null || targetUserIds.isEmpty()) {
            return;
        }

        // 受信者リスト共通の前処理（イベント再解決）は全体で1回。ここが失敗したら誰にも送れない。
        EventEntity eventEntity;
        try {
            eventEntity = eventRepository.findById(event.eventId()).orElse(null);
        } catch (Exception e) {
            log.error("解散通知のイベント解決に失敗しました: eventId={}, teamId={}, operatorUserId={}",
                    event.eventId(), event.teamId(), event.operatorUserId(), e);
            return;
        }
        if (eventEntity == null) {
            log.error("解散通知のイベント解決に失敗しました（イベントが見つかりません）: eventId={}, teamId={}",
                    event.eventId(), event.teamId());
            return;
        }
        String eventLabel = resolveEventLabel(eventEntity);

        // locale は一括解決（N+1 防止）。解決自体の失敗は既定 locale で継続する。
        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(targetUserIds);
        } catch (Exception e) {
            log.warn("解散通知の locale 一括解決に失敗（既定 locale で継続）: eventId={}, error={}",
                    event.eventId(), e.getMessage());
            locales = Map.of();
        }

        int denied = 0;
        int failed = 0;
        Long firstFailedUserId = null;
        for (Long targetUserId : targetUserIds) {
            try {
                // 組み立ても受信者単位で内側 try に入れる。
                NotificationDeliveryRequest request = buildRequest(
                        targetUserId, event, eventLabel,
                        Locale.forLanguageTag(locales.getOrDefault(targetUserId, "ja")));
                NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
                if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                    // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                    denied++;
                    log.warn("解散通知が visibility deny によりスキップされました: "
                                    + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}",
                            request.recipientUserId(), request.notificationType(),
                            request.sourceType(), request.sourceId());
                }
            } catch (Exception e) {
                failed++;
                if (firstFailedUserId == null) {
                    firstFailedUserId = targetUserId;
                }
                log.error("解散通知の配送に失敗しました: recipientUserId={}, eventId={}, teamId={}, operatorUserId={}",
                        targetUserId, event.eventId(), event.teamId(), event.operatorUserId(), e);
            }
        }

        if (event.notifyGuardians()) {
            failed += notifyGuardiansForCareRecipients(targetUserIds, event.eventId());
        }

        // 集計ログのレベルは個別ログと揃える。deny は正常系なので WARN、例外が1件でもあれば ERROR。
        if (failed > 0 || denied > 0) {
            String summary = "解散通知一括配送の結果: eventId={}, teamId={}, total={}, failed={}, denied={}, "
                    + "firstFailedUserId={}";
            if (failed > 0) {
                log.error(summary, event.eventId(), event.teamId(), targetUserIds.size(),
                        failed, denied, firstFailedUserId);
            } else {
                log.warn(summary, event.eventId(), event.teamId(), targetUserIds.size(),
                        failed, denied, firstFailedUserId);
            }
        }
    }

    /**
     * ケア対象者の見守り者へ解散通知を送る（対象者1人ぶんずつ隔離）。
     *
     * @return 失敗したケア対象者の件数（集計ログのレベル判定に使う）
     */
    private int notifyGuardiansForCareRecipients(List<Long> targetUserIds, Long eventId) {
        int failed = 0;
        for (Long userId : targetUserIds) {
            try {
                if (careLinkService.isUnderCare(userId)) {
                    careEventNotificationService.notifyDismissal(userId, eventId);
                }
            } catch (Exception e) {
                failed++;
                log.error("見守り者への解散通知に失敗しました: eventId={}, careRecipientUserId={}",
                        eventId, userId, e);
            }
        }
        return failed;
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(Long targetUserId, EventDismissalNotificationEvent event,
                                                    String eventLabel, Locale locale) {
        String title = messageSource.getMessage(
                "notification.event.dismissal.title", new Object[]{eventLabel},
                "「" + eventLabel + "」が解散しました", locale);
        return new NotificationDeliveryRequest(
                targetUserId,
                "EVENT_DISMISSAL",
                NotificationPriority.NORMAL,
                title,
                resolveDismissalBody(event.customMessage(), locale),
                "EVENT",
                event.eventId(),
                NotificationScopeType.PERSONAL,
                targetUserId,
                "/teams/" + event.teamId() + "/events/" + event.eventId(),
                null);
    }

    /** イベント表示ラベル（subtitle 優先・slug fallback）。是正前の {@code resolveEventLabel} と同一。 */
    private String resolveEventLabel(EventEntity eventEntity) {
        String subtitle = eventEntity.getSubtitle();
        return (subtitle != null && !subtitle.isBlank()) ? subtitle : eventEntity.getSlug();
    }

    /** カスタム本文（ユーザー入力・i18n 対象外）優先、未指定なら locale 別の既定文言。是正前と同一。 */
    private String resolveDismissalBody(String customMessage, Locale locale) {
        if (customMessage != null && !customMessage.isBlank()) {
            return customMessage;
        }
        return messageSource.getMessage(
                "notification.event.dismissal.defaultBody", null, "解散しました", locale);
    }
}
