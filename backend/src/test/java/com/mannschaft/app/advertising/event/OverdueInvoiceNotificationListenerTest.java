package com.mannschaft.app.advertising.event;



import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link OverdueInvoiceNotificationListener} のユニットテスト（Issue #2834 / CMP-056 第2群ロット1）。
 *
 * <p>第1群で摘出された「受信者ごとの隔離」「deny と例外の区別」を再発させないことを固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OverdueInvoiceNotificationListener 単体テスト")
class OverdueInvoiceNotificationListenerTest {

    @Mock private NotificationDeliveryRunner notificationDeliveryRunner;
    @Mock private EmailOutboxService emailOutboxService;
    @Mock private UserLocaleCache userLocaleCache;
    @Mock private MessageSource messageSource;

    @InjectMocks
    private OverdueInvoiceNotificationListener listener;

    @BeforeEach
    void stubI18n() {
        Mockito.lenient().when(userLocaleCache.getLocales(anyList())).thenReturn(Map.of());
        Mockito.lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    private OverdueInvoiceNotificationEvent event() {
        return new OverdueInvoiceNotificationEvent(
                1L, "INV-001", LocalDate.of(2026, 8, 20), 900L,
                List.of(new OverdueInvoiceNotificationEvent.Recipient(10L, "a@example.com"),
                        new OverdueInvoiceNotificationEvent.Recipient(11L, "b@example.com")),
                List.of(99L));
    }

    @Test
    @DisplayName("組織 ADMIN は ORGANIZATION スコープ、SYSTEM_ADMIN は SYSTEM スコープで送られる")
    void スコープが受信者種別ごとに分かれる() {
        given(notificationDeliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);

        listener.onOverdueInvoiceNotification(event());

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(3)).sendOne(captor.capture());
        List<NotificationDeliveryRequest> requests = captor.getAllValues();
        assertThat(requests).extracting(NotificationDeliveryRequest::recipientUserId)
                .containsExactly(10L, 11L, 99L);
        assertThat(requests.get(0).scopeType()).isEqualTo(NotificationScopeType.ORGANIZATION);
        assertThat(requests.get(0).scopeId()).isEqualTo(900L);
        assertThat(requests.get(2).scopeType()).isEqualTo(NotificationScopeType.SYSTEM);
        assertThat(requests.get(2).scopeId()).isNull();
        assertThat(requests).allMatch(r -> "AD_INVOICE".equals(r.sourceType())
                && Long.valueOf(1L).equals(r.sourceId()));
        verify(emailOutboxService, times(2)).enqueue(any(EmailOutboxRequest.class));
    }

    @Test
    @DisplayName("1人ぶんが例外でも他の受信者への配送は続行される（巻き添えにしない）")
    void 一人の例外で他が巻き添えにならない() {
        given(notificationDeliveryRunner.sendOne(any()))
                .willThrow(new RuntimeException("模擬DB例外"))
                .willReturn(NotificationDeliveryResult.DELIVERED);

        assertThatCode(() -> listener.onOverdueInvoiceNotification(event())).doesNotThrowAnyException();

        verify(notificationDeliveryRunner, times(3)).sendOne(any());
    }

    @Test
    @DisplayName("メール登録が失敗しても、その受信者の通知は成立し後続受信者へも送られる")
    void メール失敗が通知を巻き添えにしない() {
        given(notificationDeliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);
        willThrow(new RuntimeException("模擬outbox障害"))
                .given(emailOutboxService).enqueue(any(EmailOutboxRequest.class));

        assertThatCode(() -> listener.onOverdueInvoiceNotification(event())).doesNotThrowAnyException();

        verify(notificationDeliveryRunner, times(3)).sendOne(any());
    }

    @Test
    @DisplayName("visibility deny（null 復帰）でも例外にせず、後続受信者へ送り続ける")
    void denyでもループを止めない() {
        given(notificationDeliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.VISIBILITY_DENIED);

        assertThatCode(() -> listener.onOverdueInvoiceNotification(event())).doesNotThrowAnyException();

        verify(notificationDeliveryRunner, times(3)).sendOne(any());
    }

    @Test
    @DisplayName("受信者が誰もいなければ配送 Runner を呼ばない")
    void 受信者が空なら何もしない() {
        listener.onOverdueInvoiceNotification(new OverdueInvoiceNotificationEvent(
                1L, "INV-001", LocalDate.of(2026, 8, 20), 900L, List.of(), List.of()));

        verify(notificationDeliveryRunner, never()).sendOne(any());
        verify(emailOutboxService, never()).enqueue(any(EmailOutboxRequest.class));
    }
}
