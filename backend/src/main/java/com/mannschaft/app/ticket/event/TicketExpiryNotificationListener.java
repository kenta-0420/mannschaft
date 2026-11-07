package com.mannschaft.app.ticket.event;

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

import java.util.Locale;
import java.util.function.Function;

/**
 * チケット期限切れ・期限切れ事前通知の配送リスナー（Issue #2997 / CMP-260827-1152 G6）。
 *
 * <p>期限切れ遷移の項目TX（{@code TicketExpiryRunner#expireOne}）、または事前通知バッチのTXが commit された後
 * （{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で発火する。受信者は常に所有者 1 名。</p>
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code expireTickets} が全冊の EXPIRED 遷移と通知を 1 トランザクションで行い、
 * {@code NotificationHelper#notify} は既定の {@code REQUIRED} で業務TXに参加していた。1 通の DB 例外が
 * rollback-only を残し、<b>{@code book.expire()} による EXPIRED 遷移そのものと他の冊ぶんまで巻き戻って</b>
 * いた。事前通知側も同型のバッチ全体 TX の中で通知していた。</p>
 *
 * <h2>配送意味論（是正前と同一）</h2>
 * <p>通知種別・文面・locale（所有者ごと。既定 ja）・遷移先 {@code /tickets/my}・スコープ（PERSONAL）・actor（なし）は同一。
 * 可視性判定は {@code sendOne} の事前認可に任せ、deny は例外ではなく {@code VISIBILITY_DENIED} として返る。</p>
 *
 * <h2>送信済みの印（AC-D）</h2>
 * <p>どちらにも専用の印は無い。期限切れ通知の冪等性は status が ACTIVE でなくなることで、
 * 事前通知は暦日窓で抽出する日次バッチであることで担保されている（是正前から変わらない）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketExpiryNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。チケットの期限切れ通知。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTicketExpired(TicketExpiredNotificationEvent event) {
        deliver(event.userId(), event.bookId(), "TICKET_EXPIRED", "期限切れ通知",
                locale -> new String[]{
                        messageSource.getMessage("notification.ticket.expired.title", null, "チケット期限切れ", locale),
                        messageSource.getMessage("notification.ticket.expired.body", null,
                                "お持ちのチケットが期限切れになりました。", locale)});
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。チケットの期限切れ事前通知。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTicketExpiryWarning(TicketExpiryWarningNotificationEvent event) {
        int days = event.daysBefore();
        deliver(event.userId(), event.bookId(), "TICKET_EXPIRY_WARNING", "期限切れ事前通知",
                locale -> new String[]{
                        messageSource.getMessage("notification.ticket.expiryWarning.title", null,
                                "チケット期限切れ予告", locale),
                        messageSource.getMessage("notification.ticket.expiryWarning.body", new Object[]{days},
                                "お持ちのチケットが" + days + "日後に期限切れになります。", locale)});
    }

    private void deliver(Long userId, Long bookId, String notificationType, String label,
                         Function<Locale, String[]> titleAndBody) {
        try {
            // locale は所有者 1 名ぶん。解決の失敗は既定 locale で継続する。
            Locale locale;
            try {
                locale = Locale.forLanguageTag(userLocaleCache.getLocale(userId));
            } catch (Exception e) {
                log.warn("チケット{}の locale 解決に失敗（既定 locale で継続）: userId={}, bookId={}, error={}",
                        label, userId, bookId, e.getMessage());
                locale = Locale.JAPANESE;
            }
            String[] message = titleAndBody.apply(locale);
            NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(new NotificationDeliveryRequest(
                    userId,
                    notificationType,
                    NotificationPriority.NORMAL,
                    message[0],
                    message[1],
                    "TICKET_BOOK",
                    bookId,
                    NotificationScopeType.PERSONAL,
                    null,
                    "/tickets/my",
                    null));
            if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                log.warn("チケット{}が visibility deny によりスキップされました: recipientUserId={}, bookId={}",
                        label, userId, bookId);
            }
        } catch (Exception e) {
            log.error("チケット{}の配送に失敗しました: recipientUserId={}, bookId={}", label, userId, bookId, e);
        }
    }
}
