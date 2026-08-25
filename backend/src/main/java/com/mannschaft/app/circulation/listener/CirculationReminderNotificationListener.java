package com.mannschaft.app.circulation.listener;

import com.mannschaft.app.circulation.event.CirculationReminderNotificationEvent;
import com.mannschaft.app.circulation.RecipientStatus;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import com.mannschaft.app.circulation.repository.CirculationRecipientRepository;
import com.mannschaft.app.circulation.service.CirculationService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
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
 * 回覧文書手動リマインドの通知配送リスナー（Issue #2834 / CMP-056 横展開）。
 *
 * <p>{@code CirculationService#remindDocument} の業務トランザクションが commit された後
 * （{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で発火する。<b>未押印受信者
 * （{@code PENDING}）の解決・通知の文面組み立ても本リスナーの責務</b>。業務行は AFTER_COMMIT の
 * 時点で既にコミット済みのため、ここで DB 読み取りを行っても業務トランザクションを巻き込まない。
 * 受信者ごとに {@link NotificationDeliveryRunner#sendOne}（{@code REQUIRES_NEW}）を<b>1件ずつ</b>
 * 呼ぶ。</p>
 *
 * <h2>組み立ての隔離粒度を受信者単位に揃える</h2>
 * <p>{@code EventAdvanceNoticeNotificationListener} と同型: 受信者リストの解決（全体で1回）と、
 * 受信者ごとの組み立て＋送信を分ける。前者が失敗したら誰にも送れないため外側の {@code try/catch}
 * のままでよく、後者は1受信者ずつ {@code try/catch} で包み、1受信者の失敗が他の受信者への配送に
 * 影響しないようにする。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CirculationReminderNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final CirculationService circulationService;
    private final CirculationRecipientRepository recipientRepository;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCirculationReminderNotification(CirculationReminderNotificationEvent event) {
        NotificationContext ctx;
        try {
            ctx = resolveContext(event);
        } catch (Exception e) {
            // 受信者リストの解決（全体で1回）が失敗した場合は誰にも送れないため、ここは一括で諦める。
            log.error("回覧リマインド通知の受信者解決に失敗しました: documentId={}, actorId={}",
                    event.documentId(), event.actorId(), e);
            return;
        }

        for (CirculationRecipientEntity recipient : ctx.pendings()) {
            sendToRecipient(event, ctx, recipient);
        }
    }

    /**
     * 受信者リストの解決（全体で1回）。ここが失敗したら誰にも送れないため、呼び出し元は一括で諦める。
     */
    private NotificationContext resolveContext(CirculationReminderNotificationEvent event) {
        CirculationDocumentEntity documentEntity = circulationService.findDocumentById(event.documentId());
        List<CirculationRecipientEntity> pendings = recipientRepository
                .findByDocumentIdAndStatusOrderBySortOrderAsc(event.documentId(), RecipientStatus.PENDING);

        Map<Long, String> locales;
        try {
            // 受信者ごとに locale が異なるため、ループの外で一括解決する（N+1 防止）。
            locales = userLocaleCache.getLocales(
                    pendings.stream().map(CirculationRecipientEntity::getUserId).toList());
        } catch (Exception e) {
            log.warn("locale 一括解決に失敗（既定 locale で継続）: documentId={}, error={}",
                    event.documentId(), e.getMessage());
            locales = Map.of();
        }

        return new NotificationContext(documentEntity, pendings, locales);
    }

    /** 受信者1名ぶんの組み立て＋送信を1件だけ隔離して行う。 */
    private void sendToRecipient(
            CirculationReminderNotificationEvent event, NotificationContext ctx, CirculationRecipientEntity recipient) {
        try {
            Locale locale = Locale.forLanguageTag(ctx.locales().getOrDefault(recipient.getUserId(), "ja"));
            NotificationDeliveryRequest request = new NotificationDeliveryRequest(
                    recipient.getUserId(),
                    "CIRCULATION_REMINDER",
                    NotificationPriority.NORMAL,
                    messageSource.getMessage(
                            "notification.circulation.reminder.title", null,
                            "回覧の未確認があります", locale),
                    messageSource.getMessage(
                            "notification.circulation.reminder.body",
                            new Object[]{ctx.documentEntity().getTitle()},
                            "「" + ctx.documentEntity().getTitle() + "」の押印をお願いします。", locale),
                    "CIRCULATION_DOCUMENT",
                    event.documentId(),
                    scopeTypeToNotificationScope(ctx.documentEntity().getScopeType()),
                    ctx.documentEntity().getScopeId(),
                    "/circulations/" + event.documentId(),
                    event.actorId());
            NotificationEntity created = notificationDeliveryRunner.sendOne(request);
            if (created == null) {
                log.warn("回覧リマインド通知が visibility deny によりスキップされました: "
                                + "recipientUserId={}, documentId={}",
                        recipient.getUserId(), event.documentId());
            }
        } catch (Exception e) {
            // 通知失敗を隔離し、他の受信者への配信を継続する。
            log.error("回覧リマインド通知の配送に失敗しました: documentId={}, userId={}",
                    event.documentId(), recipient.getUserId(), e);
        }
    }

    /**
     * scope_type 文字列を NotificationScopeType に変換する
     * （{@code CirculationService#scopeTypeToNotificationScope} と同型）。
     */
    private NotificationScopeType scopeTypeToNotificationScope(String scopeType) {
        if ("TEAM".equals(scopeType)) {
            return NotificationScopeType.TEAM;
        }
        if ("ORGANIZATION".equals(scopeType)) {
            return NotificationScopeType.ORGANIZATION;
        }
        return NotificationScopeType.PERSONAL;
    }

    /**
     * 受信者リストの解決結果（全体で1回だけ算出する共通情報）。
     */
    private record NotificationContext(
            CirculationDocumentEntity documentEntity,
            List<CirculationRecipientEntity> pendings,
            Map<Long, String> locales) {
    }
}
