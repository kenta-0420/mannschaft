package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationSettingsRepository;
import com.mannschaft.app.notification.confirmable.event.ConfirmableReminderNotificationEvent;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.auth.entity.UserEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ConfirmableNotificationReminderRunner} の単体テスト。
 * リマインドバッチのビジネスロジック（送信条件・除外判定）を検証する。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConfirmableNotificationReminderRunner 単体テスト")
class ConfirmableNotificationReminderRunnerTest {

    @Mock
    private ConfirmableNotificationRepository notificationRepository;

    @Mock
    private ConfirmableNotificationRecipientRepository recipientRepository;

    @Mock
    private ConfirmableNotificationSettingsRepository settingsRepository;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Mock
    private UserLocaleCache userLocaleCache;

    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private ConfirmableNotificationReminderRunner runner;

    // Issue #2715 ロットB: 依存追加に伴う mock 漏れ対策（本クラスは @MockitoSettings LENIENT のため
    // 未使用でも失敗しない。processAlertIfNeeded が userLocaleCache/messageSource を使うため NPE を防ぐ）。
    @org.junit.jupiter.api.BeforeEach
    void setUpLocaleStubs() {
        given(userLocaleCache.getLocale(any())).willReturn("ja");
        given(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .willAnswer(inv -> inv.getArgument(2));
    }

    // ========================================
    // テスト用ヘルパー
    // ========================================

    private ConfirmableNotificationEntity createActiveNotificationMock(Long id) {
        ConfirmableNotificationEntity notification = mock(ConfirmableNotificationEntity.class);
        given(notification.getId()).willReturn(id);
        given(notification.getScopeType()).willReturn(ScopeType.TEAM);
        given(notification.getScopeId()).willReturn(10L);
        given(notification.getTitle()).willReturn("テスト確認通知");
        given(notification.getBody()).willReturn(null);
        given(notification.getActionUrl()).willReturn(null);
        given(notification.getPriority()).willReturn(ConfirmableNotificationPriority.NORMAL);
        given(notification.getDeadlineAt()).willReturn(null);
        return notification;
    }

    private ConfirmableNotificationRecipientEntity createRecipientNeedingFirstReminder(Long userId) {
        UserEntity user = mock(UserEntity.class);
        given(user.getId()).willReturn(userId);

        ConfirmableNotificationRecipientEntity recipient =
                mock(ConfirmableNotificationRecipientEntity.class);
        given(recipient.getUser()).willReturn(user);
        given(recipient.needsFirstReminder(any(LocalDateTime.class))).willReturn(true);
        given(recipient.needsSecondReminder(any(LocalDateTime.class))).willReturn(false);
        return recipient;
    }

    private ConfirmableNotificationRecipientEntity createRecipientNeedingNoReminder(Long userId) {
        UserEntity user = mock(UserEntity.class);
        given(user.getId()).willReturn(userId);

        ConfirmableNotificationRecipientEntity recipient =
                mock(ConfirmableNotificationRecipientEntity.class);
        given(recipient.getUser()).willReturn(user);
        given(recipient.needsFirstReminder(any(LocalDateTime.class))).willReturn(false);
        given(recipient.needsSecondReminder(any(LocalDateTime.class))).willReturn(false);
        return recipient;
    }

    // ========================================
    // remindRecipient（受信者1人ぶん）
    // ========================================

    private ConfirmableNotificationRecipientEntity recipientOf(
            ConfirmableNotificationEntity notification, ConfirmableNotificationRecipientEntity recipient) {
        given(recipient.getConfirmableNotification()).willReturn(notification);
        given(recipient.getIsConfirmed()).willReturn(false);
        given(recipient.getExcludedAt()).willReturn(null);
        given(notification.getStatus()).willReturn(
                com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus.ACTIVE);
        given(recipientRepository.findById(5L)).willReturn(java.util.Optional.of(recipient));
        return recipient;
    }

    @Nested
    @DisplayName("remindRecipient")
    class RemindRecipient {

        @Test
        @DisplayName("1回目リマインド対象なら送信済みを記録し、通知配送要求イベントを publish する（通知は直接作らない）")
        void 一回目リマインドを確定してイベントをpublishする() {
            ConfirmableNotificationEntity notification = createActiveNotificationMock(100L);
            ConfirmableNotificationRecipientEntity recipient =
                    recipientOf(notification, createRecipientNeedingFirstReminder(1L));

            int sent = runner.remindRecipient(5L, LocalDateTime.now());

            assertThat(sent).isEqualTo(1);
            verify(recipient).markFirstReminderSent();
            verify(recipientRepository).save(recipient);
            org.mockito.ArgumentCaptor<ConfirmableReminderNotificationEvent> captor =
                    org.mockito.ArgumentCaptor.forClass(ConfirmableReminderNotificationEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            NotificationDeliveryRequest request = captor.getValue().request();
            assertThat(request.recipientUserId()).isEqualTo(1L);
            assertThat(request.notificationType()).isEqualTo("CONFIRMABLE_NOTIFICATION_REMINDER_1");
            assertThat(request.sourceId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("リマインド不要の受信者は記録もイベントも出さない")
        void リマインド不要なら何もしない() {
            ConfirmableNotificationEntity notification = createActiveNotificationMock(100L);
            ConfirmableNotificationRecipientEntity recipient =
                    recipientOf(notification, createRecipientNeedingNoReminder(1L));

            int sent = runner.remindRecipient(5L, LocalDateTime.now());

            assertThat(sent).isEqualTo(0);
            verify(recipientRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("通知が ACTIVE でなければ（取消・期限切れ）リマインドしない")
        void ACTIVE以外はスキップ() {
            ConfirmableNotificationEntity notification = createActiveNotificationMock(100L);
            ConfirmableNotificationRecipientEntity recipient =
                    recipientOf(notification, createRecipientNeedingFirstReminder(1L));
            given(notification.getStatus()).willReturn(
                    com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus.CANCELLED);

            int sent = runner.remindRecipient(5L, LocalDateTime.now());

            assertThat(sent).isEqualTo(0);
            verify(eventPublisher, never()).publishEvent(any(Object.class));
        }
    }

    // ========================================
    // alertIfNeeded（送信者アラート）
    // ========================================

    @Nested
    @DisplayName("alertIfNeeded")
    class AlertIfNeeded {

        @Test
        @DisplayName("Issue #2715 ロットB: 送信者 locale が en の場合、アラート件名・本文が英語で組み立てられプレースホルダが残らない")
        void 送信者ロケールがenなら英語件名本文になる() {
            var realMessageSource = new org.springframework.context.support.ResourceBundleMessageSource();
            realMessageSource.setBasename("messages");
            realMessageSource.setDefaultEncoding("UTF-8");
            org.springframework.test.util.ReflectionTestUtils.setField(
                    runner, "messageSource", realMessageSource);

            UserEntity creator = mock(UserEntity.class);
            given(creator.getId()).willReturn(7L);
            given(userLocaleCache.getLocale(7L)).willReturn("en");

            ConfirmableNotificationEntity notification = mock(ConfirmableNotificationEntity.class);
            given(notification.getId()).willReturn(100L);
            given(notification.getStatus()).willReturn(
                    com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus.ACTIVE);
            given(notification.getCreatedBy()).willReturn(creator);
            given(notification.getScopeType()).willReturn(ScopeType.TEAM);
            given(notification.getScopeId()).willReturn(10L);
            given(notification.getTitle()).willReturn("テスト確認通知");
            given(notification.getActionUrl()).willReturn(null);
            given(notificationRepository.findById(100L)).willReturn(java.util.Optional.of(notification));

            given(settingsRepository.findByScopeTypeAndScopeId(any(), any())).willReturn(java.util.Optional.empty());
            given(recipientRepository.countByConfirmableNotificationIdAndIsConfirmedTrue(100L)).willReturn(1L);
            given(recipientRepository.countByConfirmableNotificationIdAndExcludedAtIsNull(100L)).willReturn(2L);

            boolean alerted = runner.alertIfNeeded(100L);

            assertThat(alerted).isTrue();
            org.mockito.ArgumentCaptor<ConfirmableReminderNotificationEvent> captor =
                    org.mockito.ArgumentCaptor.forClass(ConfirmableReminderNotificationEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            NotificationDeliveryRequest request = captor.getValue().request();
            assertThat(request.recipientUserId()).isEqualTo(7L);
            assertThat(request.notificationType()).isEqualTo("CONFIRMABLE_NOTIFICATION_SENDER_ALERT");
            assertThat(request.title()).isEqualTo("Confirmation notification alert");
            assertThat(request.body()).doesNotContain("{0}").doesNotContain("{1}").doesNotContain("{2}")
                    .contains("50").contains("テスト確認通知");
        }
    }
}
