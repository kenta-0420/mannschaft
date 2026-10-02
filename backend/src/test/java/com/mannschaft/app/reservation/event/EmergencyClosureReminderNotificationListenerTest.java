package com.mannschaft.app.reservation.event;



import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.reservation.entity.EmergencyClosureConfirmationEntity;
import com.mannschaft.app.reservation.entity.EmergencyClosureEntity;
import com.mannschaft.app.reservation.repository.EmergencyClosureConfirmationRepository;
import com.mannschaft.app.reservation.repository.EmergencyClosureRepository;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
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
 *
 * <p>検分是正: 文面の材料（予約日時・件名・理由・本文・チームID・実行者ID）は<b>イベントではなく
 * {@code confirmationId} / {@code closureId} からの読み直し</b>で得る。その経路と、
 * 読み直せなかった場合に配送を中止する挙動もここで固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmergencyClosureReminderNotificationListener 単体テスト")
class EmergencyClosureReminderNotificationListenerTest {

    @Mock private NotificationDeliveryRunner notificationDeliveryRunner;
    @Mock private EmailOutboxService emailOutboxService;
    @Mock private MessageSource messageSource;
    @Mock private EmergencyClosureConfirmationRepository confirmationRepository;
    @Mock private EmergencyClosureRepository closureRepository;

    @InjectMocks
    private EmergencyClosureReminderNotificationListener listener;

    @BeforeEach
    void stubI18n() {
        Mockito.lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    @BeforeEach
    void stubSources() {
        EmergencyClosureConfirmationEntity confirmation = Mockito.mock(EmergencyClosureConfirmationEntity.class);
        Mockito.lenient().when(confirmation.getAppointmentAt())
                .thenReturn(LocalDateTime.of(2026, 8, 26, 15, 0));
        Mockito.lenient().when(confirmation.getUserId()).thenReturn(100L);

        EmergencyClosureEntity closure = Mockito.mock(EmergencyClosureEntity.class);
        Mockito.lenient().when(closure.getId()).thenReturn(10L);
        Mockito.lenient().when(closure.getTeamId()).thenReturn(500L);
        Mockito.lenient().when(closure.getSubject()).thenReturn("臨時休業");
        Mockito.lenient().when(closure.getReason()).thenReturn("設備点検");
        Mockito.lenient().when(closure.getMessageBody()).thenReturn("本日は休業します");
        Mockito.lenient().when(closure.getCreatedBy()).thenReturn(900L);

        Mockito.lenient().when(confirmationRepository.findById(1L)).thenReturn(Optional.of(confirmation));
        Mockito.lenient().when(closureRepository.findById(10L)).thenReturn(Optional.of(closure));
    }

    private EmergencyClosureReminderNotificationEvent event(
            EmergencyClosureReminderNotificationEvent.Phase phase, Long recipientId, String email) {
        return new EmergencyClosureReminderNotificationEvent(
                phase, 1L, 10L, "山田 太郎", recipientId, email, "ja");
    }

    @Test
    @DisplayName("患者宛は EMERGENCY_CLOSURE 種別・TEAM スコープ・URGENT で送られメールも登録される")
    void 患者宛の通知内容() {
        given(notificationDeliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);

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
        given(notificationDeliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);

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
        given(notificationDeliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.VISIBILITY_DENIED);

        assertThatCode(() -> listener.onEmergencyClosureReminderNotification(
                event(EmergencyClosureReminderNotificationEvent.Phase.PATIENT, 100L, "p@example.com")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("メール登録が失敗しても通知は成立し例外を伝播させない")
    void メール失敗を伝播させない() {
        given(notificationDeliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);
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
        given(notificationDeliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);

        listener.onEmergencyClosureReminderNotification(
                event(EmergencyClosureReminderNotificationEvent.Phase.PATIENT, 100L, null));

        Mockito.verifyNoInteractions(emailOutboxService);
    }

    @Test
    @DisplayName("確認行が読み直せなければ配送しない（通知もメールも出さない）")
    void 確認行が読めなければ配送しない() {
        given(confirmationRepository.findById(1L)).willReturn(Optional.empty());

        assertThatCode(() -> listener.onEmergencyClosureReminderNotification(
                event(EmergencyClosureReminderNotificationEvent.Phase.PATIENT, 100L, "p@example.com")))
                .doesNotThrowAnyException();

        Mockito.verifyNoInteractions(notificationDeliveryRunner);
        Mockito.verifyNoInteractions(emailOutboxService);
    }

    @Test
    @DisplayName("臨時休業行が読み直せなければ配送しない")
    void 臨時休業行が読めなければ配送しない() {
        given(closureRepository.findById(10L)).willReturn(Optional.empty());

        assertThatCode(() -> listener.onEmergencyClosureReminderNotification(
                event(EmergencyClosureReminderNotificationEvent.Phase.PATIENT, 100L, "p@example.com")))
                .doesNotThrowAnyException();

        Mockito.verifyNoInteractions(notificationDeliveryRunner);
        Mockito.verifyNoInteractions(emailOutboxService);
    }

    @Test
    @DisplayName("読み直しが例外でも伝播させず配送を中止する")
    void 読み直しの例外を伝播させない() {
        given(confirmationRepository.findById(1L)).willThrow(new RuntimeException("模擬DB例外"));

        assertThatCode(() -> listener.onEmergencyClosureReminderNotification(
                event(EmergencyClosureReminderNotificationEvent.Phase.PATIENT, 100L, "p@example.com")))
                .doesNotThrowAnyException();

        Mockito.verifyNoInteractions(notificationDeliveryRunner);
        Mockito.verifyNoInteractions(emailOutboxService);
    }

    @Test
    @DisplayName("メールの冪等キーは読み直した患者ユーザーIDで組み立てる")
    void メール冪等キーは読み直した患者ID() {
        given(notificationDeliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);

        listener.onEmergencyClosureReminderNotification(
                event(EmergencyClosureReminderNotificationEvent.Phase.PATIENT, 100L, "p@example.com"));

        ArgumentCaptor<EmailOutboxRequest> captor = ArgumentCaptor.forClass(EmailOutboxRequest.class);
        verify(emailOutboxService).enqueue(captor.capture());
        assertThat(captor.getValue().sourceEventId()).isEqualTo("emergency-reminder:10:100");
    }
}
