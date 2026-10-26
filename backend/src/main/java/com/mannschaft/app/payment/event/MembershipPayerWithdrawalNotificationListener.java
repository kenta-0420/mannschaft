package com.mannschaft.app.payment.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.payment.connect.ScopeKind;
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
 * 柱③-B（CMP-260901-1538）PR-3: 払い手の退会に伴う継続課金の期末解約を受益者へ通知するリスナー（AC-13）。
 *
 * <p>金型は {@link PaymentAdvanceSettledNotificationListener}。業務トランザクションの commit 後
 * （{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で発火し、受信者は 1 名（受益者）である。
 * locale は {@code LocaleContextHolder} ではなく {@link UserLocaleCache#getLocales} で解決する
 * （本リスナーは別スレッドで動くため退会操作者の locale は使えず、また使うべきでもない）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MembershipPayerWithdrawalNotificationListener {

    /** 通知種別。 */
    private static final String NOTIFICATION_TYPE = "MEMBERSHIP_PAYER_WITHDRAWAL_CANCELLED";

    /** sourceType（F00 visibility マッパー未登録＝fail-soft で素通り。{@code sourceId} も null）。 */
    private static final String SOURCE_TYPE = "MEMBERSHIP_SUBSCRIPTION";

    /** 遷移先（受益者は払い手ではないため、自分が払い手の一覧ではなく共通の継続課金一覧へ導く）。 */
    private static final String ACTION_URL = "/me/membership-subscriptions";

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
            gateKeys = "FEATURE_BILLING_PAYMENT_ENABLED",
            reason = "決済・課金を閉栓すれば継続課金そのものが動かず、この通知は期末解約の付随予告に過ぎない。再生されず失われても Stripe 側の期末解約予約と DB の状態は壊れない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMembershipPayerWithdrawalNotification(MembershipPayerWithdrawalNotificationEvent event) {
        Long recipientUserId = event.beneficiaryUserId();
        if (recipientUserId == null) {
            log.debug("払い手退会に伴う期末解約通知: 受益者不在のためスキップ subscriptionId={}", event.subscriptionId());
            return;
        }

        // locale 解決の失敗は既定 locale で継続する（通知自体は届けるべきため）。
        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(List.of(recipientUserId));
        } catch (Exception e) {
            log.warn("払い手退会に伴う期末解約通知の locale 解決に失敗（既定 locale で継続）: subscriptionId={}, error={}",
                    event.subscriptionId(), e.getMessage());
            locales = Map.of();
        }
        Locale locale = Locale.forLanguageTag(locales.getOrDefault(recipientUserId, "ja"));

        try {
            NotificationDeliveryRequest request = buildRequest(recipientUserId, event, locale);
            NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
            if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                log.warn("払い手退会に伴う期末解約通知が visibility deny によりスキップされました: "
                                + "recipientUserId={}, subscriptionId={}",
                        recipientUserId, event.subscriptionId());
            }
        } catch (Exception e) {
            log.error("払い手退会に伴う期末解約通知の配送に失敗しました: recipientUserId={}, subscriptionId={}",
                    recipientUserId, event.subscriptionId(), e);
        }
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(Long recipientUserId,
                                                    MembershipPayerWithdrawalNotificationEvent event,
                                                    Locale locale) {
        String periodEndText = event.currentPeriodEnd() != null
                ? event.currentPeriodEnd().toString() : "";
        return new NotificationDeliveryRequest(
                recipientUserId,
                NOTIFICATION_TYPE,
                NotificationPriority.HIGH,
                messageSource.getMessage(
                        "notification.membership.payer_withdrawal.title", null,
                        "メンバーシップが期末で終了します", locale),
                messageSource.getMessage(
                        "notification.membership.payer_withdrawal.body",
                        new Object[]{periodEndText},
                        "お支払いを担当していた方の退会に伴い、あなたのメンバーシップは現在の期間の終了（" + periodEndText
                                + "）をもって終了します。継続をご希望の場合は、あらためてお申し込みください。",
                        locale),
                SOURCE_TYPE,
                null,
                toNotificationScopeType(event.scopeKind()),
                event.scopeId(),
                ACTION_URL,
                event.payerUserId());
    }

    /**
     * payment の {@link ScopeKind} を通知の {@link NotificationScopeType} へ変換する。
     *
     * <p>{@code ORG} → {@code ORGANIZATION} の<b>綴り変換</b>が必要
     * （{@code NotificationScopeType.valueOf("ORG")} は不一致で即死する）。</p>
     */
    private NotificationScopeType toNotificationScopeType(ScopeKind scopeKind) {
        return scopeKind == ScopeKind.ORG ? NotificationScopeType.ORGANIZATION : NotificationScopeType.TEAM;
    }
}
