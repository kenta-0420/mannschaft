package com.mannschaft.app.reservation.event;

import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
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

import java.time.LocalDateTime;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link EmergencyClosureReminderNotificationListener} のユニットテスト
 * （Issue #2834 / CMP-056 第2群ロット1）。
 *
 * <p>患者宛／送信者宛で通知種別が分かれること、deny と例外を区別してどちらも伝播させないこと、
 * メール登録の失敗が通知を巻き添えにしないことを固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmergencyClosureReminderNotificationListener 単体テスト")
class EmergencyClosureReminderNotificationListenerTest {

    @Mock private NotificationDeliveryRunner notificationDeliveryRunner;
    @Mock private EmailOutboxService emailOutboxService;
    @Mock private MessageSource messageSource;

    @InjectMocks
    private EmergencyClosureReminderNotificationListener listener;

    @BeforeEach
    void stubI18n() {
        Mockito.lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    private EmergencyClosureReminderNotificationEvent event(
            EmergencyClosureReminderNotificationEvent.Phase phase, Long recipientId, String email) {
        return new EmergencyClosureReminderNotificationEvent(
                phase, 1L, 10L, 500L, "臨時休業", "設備点検", "本日は休業します",
                LocalDateTime.of(2026, 8, 26, 15, 0), 100L, "山田 太郎",
                recipientId, email, "ja",
                phase == EmergencyClosureReminderNotificationEvent.Phase.PATIENT ? 900L : null);
    }

    @Test
    @DisplayName("患者宛は EMERGENCY_CLOSURE 種別・TEAM スコープ・URGENT で送られメールも登録される")
    void 患者宛の通知内容() {
        given(notificationDeliveryRunner.sendOne(any())).willReturn(Mockito.mock(NotificationEntity.class));

        listener.onEmergencyClosureReminderNotification(
                event(EmergencyClosureReminderNotificationEvent.Phase.PATIENT, 100L, "p@example.com"));

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner).sendOne(captor.capture());
        NotificationDeliveryRequest request = captor.getValue();
        assertThat(request.notificationType()).isEqualTo("EMERGENCY_CLOSURE");
        assertThat(request.priority()).isEqualTo(NotificationPriority.URGENT);
        assertThat(request.scopeType()).isEqualTo(NotificationScopeType.TEAM);
        assertThat(request.scopeId()).isEqualTo(500L);
        assertThat(request.sourceType()).isEqualTo("EMERGENCY_CLOSURE");
        assertThat(request.sourceId()).isEqualTo(10L);
        assertThat(request.actorId()).isEqualTo(900L);
        verify(emailOutboxService).enqueue(any(EmailOutboxRequest.class));
    }

    @Test
    @DisplayName("送信者宛は CLOSURE_UNCONFIRMED_REMINDER 種別で送られる")
    void 送信者宛の通知種別() {
        given(notificationDeliveryRunner.sendOne(any())).willReturn(Mockito.mock(NotificationEntity.class));

        listener.onEmergencyClosureReminderNotification(
                event(EmergencyClosureReminderNotificationEvent.Phase.OPERATOR, 900L, "op@example.com"));

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner).sendOne(captor.capture());
        assertThat(captor.getValue().notificationType()).isEqualTo("CLOSURE_UNCONFIRMED_REMINDER");
        assertThat(captor.getValue().actorId()).isNull();
    }

    @Test
    @DisplayName("配送が例外でもバッチ側へ伝播させない（記録は既にコミット済み）")
    void 例外を伝播させない() {
        given(notificationDeliveryRunner.sendOne(any())).willThrow(new RuntimeException("模擬DB例外"));

        assertThatCode(() -> listener.onEmergencyClosureReminderNotification(
                event(EmergencyClosureReminderNotificationEvent.Phase.PATIENT, 100L, "p@example.com")))
                .doesNotThrowAnyException();

        // 通知が落ちてもメールは独立して試行される。
        verify(emailOutboxService).enqueue(any(EmailOutboxRequest.class));
    }

    @Test
    @DisplayName("visibility deny（null 復帰）でも例外にしない")
    void denyでも例外にしない() {
        given(notificationDeliveryRunner.sendOne(any())).willReturn(null);

        assertThatCode(() -> listener.onEmergencyClosureReminderNotification(
                event(EmergencyClosureReminderNotificationEvent.Phase.PATIENT, 100L, "p@example.com")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("メール登録が失敗しても通知は成立し例外を伝播させない")
    void メール失敗を伝播させない() {
        given(notificationDeliveryRunner.sendOne(any())).willReturn(Mockito.mock(NotificationEntity.class));
        willThrow(new RuntimeException("模擬outbox障害"))
                .given(emailOutboxService).enqueue(any(EmailOutboxRequest.class));

        assertThatCode(() -> listener.onEmergencyClosureReminderNotification(
                event(EmergencyClosureReminderNotificationEvent.Phase.PATIENT, 100L, "p@example.com")))
                .doesNotThrowAnyException();

        verify(notificationDeliveryRunner).sendOne(any());
    }

    @Test
    @DisplayName("メールアドレスが無ければ outbox に登録しない")
    void メール無しなら登録しない() {
        given(notificationDeliveryRunner.sendOne(any())).willReturn(Mockito.mock(NotificationEntity.class));

        listener.onEmergencyClosureReminderNotification(
                event(EmergencyClosureReminderNotificationEvent.Phase.PATIENT, 100L, null));

        Mockito.verifyNoInteractions(emailOutboxService);
    }
}
