package com.mannschaft.app.ticket;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.ticket.event.TicketExpiredNotificationEvent;
import com.mannschaft.app.ticket.event.TicketExpiryNotificationListener;
import com.mannschaft.app.ticket.event.TicketExpiryWarningNotificationEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link TicketExpiryNotificationListener} のユニットテスト（Issue #2997 / CMP-260827-1152 G6）。
 *
 * <p>是正前は {@code TicketExpiryBatchServiceTest} が {@code notificationHelper.notify} の引数として固定していた
 * 配送の意味論（通知種別・source・スコープ・遷移先・文面・locale・日数プレースホルダ）を、配送リスナー側で固定し直す。
 * 実物の {@code MessageSource} を使う。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TicketExpiryNotificationListener 単体テスト")
class TicketExpiryNotificationListenerTest {

    @Mock private NotificationDeliveryRunner notificationDeliveryRunner;
    @Mock private UserLocaleCache userLocaleCache;

    private TicketExpiryNotificationListener listener;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        listener = new TicketExpiryNotificationListener(notificationDeliveryRunner, userLocaleCache, messageSource);
        Mockito.lenient().when(userLocaleCache.getLocale(anyLong())).thenReturn("ja");
        Mockito.lenient().when(notificationDeliveryRunner.sendOne(any()))
                .thenReturn(NotificationDeliveryResult.DELIVERED);
    }

    private NotificationDeliveryRequest sentOnce() {
        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner).sendOne(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("期限切れ: 通知種別・source・スコープ・遷移先が是正前と同じ配送要求になる")
    void expiredKeepsSemantics() {
        listener.onTicketExpired(new TicketExpiredNotificationEvent(7L, 100L));

        NotificationDeliveryRequest r = sentOnce();
        assertThat(r.recipientUserId()).isEqualTo(100L);
        assertThat(r.notificationType()).isEqualTo("TICKET_EXPIRED");
        assertThat(r.sourceType()).isEqualTo("TICKET_BOOK");
        assertThat(r.sourceId()).isEqualTo(7L);
        assertThat(r.scopeType()).isEqualTo(NotificationScopeType.PERSONAL);
        assertThat(r.scopeId()).isNull();
        assertThat(r.actionUrl()).isEqualTo("/tickets/my");
        assertThat(r.actorId()).isNull();
    }

    @Test
    @DisplayName("期限切れ: 受信者 locale が en の場合、件名・本文が英語で組み立てられプレースホルダが残らない")
    void expiredEnglish() {
        given(userLocaleCache.getLocale(200L)).willReturn("en");

        listener.onTicketExpired(new TicketExpiredNotificationEvent(7L, 200L));

        NotificationDeliveryRequest r = sentOnce();
        assertThat(r.title()).isEqualTo("Ticket expired");
        assertThat(r.body()).doesNotContain("{0}");
    }

    @Test
    @DisplayName("事前通知: 通知種別が TICKET_EXPIRY_WARNING で、日本語本文に日数が入る")
    void warningJapaneseWithDays() {
        listener.onTicketExpiryWarning(new TicketExpiryWarningNotificationEvent(7L, 100L, 7));

        NotificationDeliveryRequest r = sentOnce();
        assertThat(r.notificationType()).isEqualTo("TICKET_EXPIRY_WARNING");
        assertThat(r.body()).contains("7");
    }

    @Test
    @DisplayName("事前通知: locale=en で {0}(日数) が置換された英語本文になる")
    void warningEnglishPlaceholderReplaced() {
        given(userLocaleCache.getLocale(300L)).willReturn("en");

        listener.onTicketExpiryWarning(new TicketExpiryWarningNotificationEvent(7L, 300L, 30));

        NotificationDeliveryRequest r = sentOnce();
        assertThat(r.body()).doesNotContain("{0}").contains("30");
    }

    @Test
    @DisplayName("配送が例外でもリスナーは例外を投げない（非同期スレッドへ漏らさない）")
    void deliveryFailureIsContained() {
        given(notificationDeliveryRunner.sendOne(any())).willThrow(new RuntimeException("模擬DB例外"));

        assertThatCode(() -> listener.onTicketExpired(new TicketExpiredNotificationEvent(7L, 100L)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("visibility deny でも例外にしない")
    void denyIsNotAnError() {
        given(notificationDeliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.VISIBILITY_DENIED);

        assertThatCode(() -> listener.onTicketExpiryWarning(new TicketExpiryWarningNotificationEvent(7L, 100L, 1)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("locale の解決が失敗しても既定 locale（ja）で配送を続行する")
    void localeFailureFallsBackToJa() {
        given(userLocaleCache.getLocale(100L)).willThrow(new RuntimeException("模擬キャッシュ障害"));

        listener.onTicketExpired(new TicketExpiredNotificationEvent(7L, 100L));

        assertThat(sentOnce().title()).isEqualTo("チケット期限切れ");
    }
}
