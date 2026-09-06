package com.mannschaft.app.billing;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
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
 * 柱③-B 請求担当引継（CMP-260901-1538）: 引継フローの通知配送リスナー（設計書 §5.2・§5.5・§3.6）。
 *
 * <p>業務トランザクションの commit 後（{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で発火する。
 * 業務 tx 内で {@code NotificationService} を直接呼ぶと、通知側の DB エラーが rollback-only を立てて
 * <b>引継要求の作成・承諾そのものを巻き戻す</b>ため、業務側は {@link BillingPayerHandoverNotificationEvent}
 * の発行だけを行う。金型は {@code payment.event.PaymentAdvanceSettledNotificationListener}。</p>
 *
 * <p>locale は {@code LocaleContextHolder} ではなく {@link UserLocaleCache#getLocales} で
 * <b>受信者ごと</b>にバルク解決する（本リスナーは別スレッドで動くため操作者の locale は使えず、
 * また使うべきでもない）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingPayerHandoverNotificationListener {

    /** visibility マッパー未登録＝fail-soft で素通り（金型と同様に {@code sourceId} も null）。 */
    private static final String SOURCE_TYPE = "BILLING_PAYER_HANDOVER";

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
            gateKeys = "FEATURE_BILLING_PAYMENT_ENABLED",
            reason = "決済・課金を閉栓すれば請求担当の引継フロー自体が起こらず、この通知は付随通知に過ぎない。再生されず失われても引継要求の状態機械と Stripe 側の予約は壊れない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBillingPayerHandoverNotification(BillingPayerHandoverNotificationEvent event) {
        List<Long> recipients = event.recipientUserIds();
        if (recipients == null || recipients.isEmpty()) {
            log.debug("柱③-B 引継通知: 宛先不在のためスキップ kind={}, handoverRequestId={}",
                    event.kind(), event.handoverRequestId());
            return;
        }

        // locale は一括解決（N+1 防止）。解決自体の失敗は既定 locale で継続する。
        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(recipients);
        } catch (Exception e) {
            log.warn("柱③-B 引継通知の locale 一括解決に失敗（既定 locale で継続）: handoverRequestId={}, error={}",
                    event.handoverRequestId(), e.getMessage());
            locales = Map.of();
        }

        int denied = 0;
        int failed = 0;
        Long firstFailedUserId = null;
        for (Long recipientUserId : recipients) {
            try {
                NotificationDeliveryRequest request = buildRequest(recipientUserId, event,
                        Locale.forLanguageTag(locales.getOrDefault(recipientUserId, "ja")));
                NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
                if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                    denied++;
                    log.warn("柱③-B 引継通知が visibility deny によりスキップされました: "
                                    + "recipientUserId={}, notificationType={}, handoverRequestId={}",
                            recipientUserId, request.notificationType(), event.handoverRequestId());
                }
            } catch (Exception e) {
                failed++;
                if (firstFailedUserId == null) {
                    firstFailedUserId = recipientUserId;
                }
                log.error("柱③-B 引継通知の配送に失敗しました: recipientUserId={}, kind={}, handoverRequestId={}",
                        recipientUserId, event.kind(), event.handoverRequestId(), e);
            }
        }

        if (failed > 0 || denied > 0) {
            String summary = "柱③-B 引継通知一括配送の結果: kind={}, handoverRequestId={}, total={}, "
                    + "failed={}, denied={}, firstFailedUserId={}";
            if (failed > 0) {
                log.error(summary, event.kind(), event.handoverRequestId(), recipients.size(),
                        failed, denied, firstFailedUserId);
            } else {
                log.warn(summary, event.kind(), event.handoverRequestId(), recipients.size(),
                        failed, denied, firstFailedUserId);
            }
        }
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(
            Long recipientUserId, BillingPayerHandoverNotificationEvent event, Locale locale) {

        String messageKeyBase = switch (event.kind()) {
            case HANDOVER_REQUESTED -> "notification.billing.payer_handover.requested";
            case PAYMENT_METHOD_REQUIRED -> "notification.billing.payer_handover.payment_method_required";
            case ADDITIONAL_AUTH_REQUIRED -> "notification.billing.payer_handover.additional_auth_required";
        };
        String defaultTitle = switch (event.kind()) {
            case HANDOVER_REQUESTED -> "請求担当の引継をお願いします";
            case PAYMENT_METHOD_REQUIRED -> "お支払い方法の登録が必要です";
            case ADDITIONAL_AUTH_REQUIRED -> "カードの追加認証が必要です";
        };
        // 設計書 §2.3・AC-9: 「請求日は変わらない（新サブスクは旧期末から開始）」旨を必ず含める。
        String defaultBody = switch (event.kind()) {
            case HANDOVER_REQUESTED ->
                    "現在の請求担当者が退会予定のため、請求担当の引継を承諾していただく必要があります。"
                            + "引継後も請求日は変わりません（新しい契約は現在の契約の期末から開始されます）。";
            case PAYMENT_METHOD_REQUIRED ->
                    "有効なお支払い方法が登録されていないため、請求担当の引継を確定できませんでした。"
                            + "お支払い方法を登録してから、あらためて承諾してください。";
            case ADDITIONAL_AUTH_REQUIRED ->
                    "カード会社による追加認証が完了していません。期限までに認証を完了しないと引継が失敗します。";
        };

        return new NotificationDeliveryRequest(
                recipientUserId,
                "BILLING_PAYER_HANDOVER_" + event.kind().name(),
                NotificationPriority.HIGH,
                messageSource.getMessage(messageKeyBase + ".title", null, defaultTitle, locale),
                messageSource.getMessage(messageKeyBase + ".body", null, defaultBody, locale),
                SOURCE_TYPE,
                null,
                toNotificationScopeType(event.scopeKind()),
                event.scopeId(),
                buildActionUrl(event),
                event.actorUserId());
    }

    /**
     * billing の {@link EntitlementScopeKind} を通知の {@link NotificationScopeType} へ変換する。
     *
     * <p>{@code ORG} → {@code ORGANIZATION} の<b>綴り変換</b>が必要
     * （{@code NotificationScopeType.valueOf("ORG")} は不一致で即死する）。
     * USER スコープは引継対象外だが、防御的に {@code PERSONAL} へ倒す。</p>
     */
    private NotificationScopeType toNotificationScopeType(EntitlementScopeKind scopeKind) {
        return switch (scopeKind) {
            case TEAM -> NotificationScopeType.TEAM;
            case ORG -> NotificationScopeType.ORGANIZATION;
            case USER -> NotificationScopeType.PERSONAL;
        };
    }

    private String buildActionUrl(BillingPayerHandoverNotificationEvent event) {
        String prefix = event.scopeKind() == EntitlementScopeKind.ORG
                ? "/organizations/" : "/teams/";
        return prefix + event.scopeId() + "/billing/payer-handover-requests/" + event.handoverRequestId();
    }
}
