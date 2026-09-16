package com.mannschaft.app.payment.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.PaymentItemRepository;
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
import java.util.Optional;

/**
 * 未払いリマインドの通知配送リスナー（Issue #2990 L7）。
 *
 * <h2>是正前の欠陥: 1 人ぶんの通知失敗で一括リマインドが丸ごと落ちていた</h2>
 * <p>是正前は {@code MemberPaymentService#sendRemind}（{@code @Transactional}）が未払いメンバーを
 * ループし、その内側で {@code notificationHelper.notify} を try で囲わずに直接呼んでいた。
 * {@code createNotification} は既定の {@code REQUIRED} で {@code sendRemind} のトランザクションに参加するため:</p>
 * <ul>
 *   <li>1 人ぶんの {@code notifications} INSERT が落ちると例外が {@code sendRemind} の外へ抜け、
 *       <b>それまでに書いた通知行も含めて全員ぶんが巻き戻る</b>。</li>
 *   <li>ループも打ち切られるので、以降の未払いメンバーには<b>そもそも送信が試みられない</b>。</li>
 *   <li>管理者には 500 が返り、{@code RemindResponse} の件数すら受け取れないため
 *       「誰に届いて誰に届かなかったか」が観測できない。</li>
 * </ul>
 * <p>{@code sendRemind} 自体は業務行を書かないため「業務データが巻き戻る」形ではないが、
 * 通知の全損とループ中断という形で同じ根（通知が業務TXに参加している）から被害が出ていた。</p>
 *
 * <p>是正後は業務TXでは {@link PaymentRemindNotificationEvent} を publish するだけとし、
 * commit 後に本リスナーが受信者ごとに {@code NotificationDeliveryRunner#sendOne}（1 件＝1 独立トランザクション）
 * で配送する。1 人の失敗は ERROR ログに残して次の受信者へ進む（握りつぶさない）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRemindNotificationListener {

    /** 通知種別（是正前の {@code MemberPaymentService} と同値）。 */
    static final String TYPE_PAYMENT_REMIND = "PAYMENT_REMIND";

    /** 通知 sourceType（是正前と同値）。 */
    static final String SOURCE_TYPE_PAYMENT = "PAYMENT";

    private final PaymentItemRepository paymentItemRepository;
    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
            gateKeys = "FEATURE_BILLING_PAYMENT_ENABLED",
            reason = "決済・課金を閉栓すれば未払いという状態自体が生じず、このリマインドは付随通知に過ぎない。"
                    + "再生されず失われても支払い記録の整合性は壊れない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentRemindNotification(PaymentRemindNotificationEvent event) {
        List<Long> recipients = event.recipientUserIds();
        if (recipients == null || recipients.isEmpty()) {
            return;
        }

        // 支払項目名は業務データのためイベントに載せず読み直す。読み直せなければ配送中止（握りつぶさない）。
        String itemName;
        try {
            Optional<PaymentItemEntity> item = paymentItemRepository.findById(event.paymentItemId());
            if (item.isEmpty()) {
                log.error("未払いリマインド通知: 支払項目を読み直せません（配送中止）: paymentItemId={}",
                        event.paymentItemId());
                return;
            }
            itemName = item.get().getName();
        } catch (Exception e) {
            log.error("未払いリマインド通知: 支払項目の読み直しに失敗しました（配送中止）: paymentItemId={}",
                    event.paymentItemId(), e);
            return;
        }

        // locale の一括解決は全体で1回（N+1 防止）。失敗しても既定 locale で配送は続ける。
        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(recipients);
        } catch (Exception e) {
            log.warn("未払いリマインドの locale 一括解決に失敗（既定 locale で継続）: paymentItemId={}, error={}",
                    event.paymentItemId(), e.getMessage());
            locales = Map.of();
        }

        NotificationScopeType scopeType = event.teamId() != null
                ? NotificationScopeType.TEAM : NotificationScopeType.ORGANIZATION;

        int failed = 0;
        int denied = 0;
        Long firstFailedUserId = null;
        for (Long userId : recipients) {
            try {
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(userId, "ja"));
                String title = messageSource.getMessage(
                        "notification.payment.remind.title", null, "支払いリマインド", locale);
                String body = messageSource.getMessage(
                        "notification.payment.remind.body", new Object[]{itemName},
                        itemName + "の支払いが未完了です", locale);
                NotificationDeliveryRequest request = new NotificationDeliveryRequest(
                        userId,
                        TYPE_PAYMENT_REMIND,
                        NotificationPriority.NORMAL,
                        title,
                        body,
                        SOURCE_TYPE_PAYMENT,
                        event.paymentItemId(),
                        scopeType,
                        event.scopeId(),
                        "/payments/" + event.paymentItemId(),
                        null);
                if (notificationDeliveryRunner.sendOne(request) == NotificationDeliveryResult.VISIBILITY_DENIED) {
                    denied++;
                    log.warn("未払いリマインド通知が visibility deny によりスキップされました: "
                            + "paymentItemId={}, recipientUserId={}", event.paymentItemId(), userId);
                }
            } catch (Exception e) {
                failed++;
                if (firstFailedUserId == null) {
                    firstFailedUserId = userId;
                }
                log.error("未払いリマインド通知の配送に失敗しました（他の受信者は継続）: "
                        + "paymentItemId={}, recipientUserId={}", event.paymentItemId(), userId, e);
            }
        }

        if (failed > 0) {
            log.error("未払いリマインド一括配送の結果: paymentItemId={}, total={}, failed={}, denied={}, "
                            + "firstFailedUserId={}",
                    event.paymentItemId(), recipients.size(), failed, denied, firstFailedUserId);
        } else if (denied > 0) {
            log.warn("未払いリマインド一括配送の結果: paymentItemId={}, total={}, failed=0, denied={}",
                    event.paymentItemId(), recipients.size(), denied);
        }
    }
}
