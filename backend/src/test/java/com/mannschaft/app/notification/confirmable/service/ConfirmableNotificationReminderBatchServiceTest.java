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
import com.mannschaft.app.notification.service.NotificationHelper;
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
 * {@link ConfirmableNotificationReminderBatchService} の単体テスト。
 * リマインドバッチのビジネスロジック（送信条件・除外判定）を検証する。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConfirmableNotificationReminderBatchService 単体テスト")
class ConfirmableNotificationReminderBatchServiceTest {

    @Mock
    private ConfirmableNotificationRepository notificationRepository;

    @Mock
    private ConfirmableNotificationRecipientRepository recipientRepository;

    @Mock
    private ConfirmableNotificationSettingsRepository settingsRepository;

    @Mock
    private NotificationHelper notificationHelper;

    @Mock
    private UserLocaleCache userLocaleCache;

    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private ConfirmableNotificationReminderBatchService batchService;

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
    // processReminders（runBatchのコア処理）
    // ========================================

    @Nested
    @DisplayName("processReminders")
    class ProcessReminders {

        @Test
        @DisplayName("runBatch_1回目リマインド送信_resolvedFirstReminderMinutes以上経過した未確認受信者にリマインドが送られmarkFirstReminderSentが呼ばれる")
        void runBatch_1回目リマインド送信_resolvedFirstReminderMinutes以上経過した未確認受信者にリマインドが送られmarkFirstReminderSentが呼ばれる() {
            // given
            ConfirmableNotificationEntity notification = createActiveNotificationMock(100L);
            LocalDateTime now = LocalDateTime.now();

            ConfirmableNotificationRecipientEntity recipient =
                    createRecipientNeedingFirstReminder(1L);

            given(recipientRepository.findActiveUnconfirmedByNotificationId(100L))
                    .willReturn(List.of(recipient));
            given(recipientRepository.save(any())).willReturn(recipient);

            // when
            int sentCount = batchService.processReminders(notification, now);

            // then: 1回目リマインドが送られ、markFirstReminderSent が呼ばれる
            assertThat(sentCount).isEqualTo(1);
            verify(recipient).markFirstReminderSent();
            verify(recipientRepository).save(recipient);
            verify(notificationHelper).notify(
                    anyLong(), anyString(), any(NotificationPriority.class),
                    anyString(), anyString(), anyString(), anyLong(),
                    any(NotificationScopeType.class), anyLong(), any(), any());
        }

        @Test
        @DisplayName("runBatch_除外済み受信者はスキップ_needsFirstReminderがfalseの受信者はリマインド対象外")
        void runBatch_除外済み受信者はスキップ_needsFirstReminderがfalseの受信者はリマインド対象外() {
            // given
            ConfirmableNotificationEntity notification = createActiveNotificationMock(100L);
            LocalDateTime now = LocalDateTime.now();

            // needsFirstReminder=false（除外済みまたは既確認）の受信者
            ConfirmableNotificationRecipientEntity noReminderRecipient =
                    createRecipientNeedingNoReminder(1L);

            given(recipientRepository.findActiveUnconfirmedByNotificationId(100L))
                    .willReturn(List.of(noReminderRecipient));

            // when
            int sentCount = batchService.processReminders(notification, now);

            // then: リマインドは送られない
            assertThat(sentCount).isEqualTo(0);
            verify(notificationHelper, never()).notify(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("runBatch_ACTIVE以外はスキップ_statusがCANCELLEDの通知の受信者にはリマインドを送らない")
        void runBatch_ACTIVE以外はスキップ_statusがCANCELLEDの通知の受信者にはリマインドを送らない() {
            // given
            // CANCELLED通知の場合、findActiveUnconfirmedByNotificationId は
            // ACTIVE 条件でフィルタするため空リストを返す
            ConfirmableNotificationEntity cancelledNotification = ConfirmableNotificationEntity.builder()
                    .scopeType(ScopeType.TEAM)
                    .scopeId(10L)
                    .title("キャンセル済み通知")
                    .priority(ConfirmableNotificationPriority.NORMAL)
                    .totalRecipientCount(1)
                    .build();
            cancelledNotification.cancel(null);

            LocalDateTime now = LocalDateTime.now();

            // ACTIVE 条件のクエリ結果として空リストを返す
            given(recipientRepository.findActiveUnconfirmedByNotificationId(any()))
                    .willReturn(List.of());

            // when
            int sentCount = batchService.processReminders(cancelledNotification, now);

            // then: リマインドは送られない
            assertThat(sentCount).isEqualTo(0);
            verify(notificationHelper, never()).notify(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ========================================
    // processAlertIfNeeded（送信者アラート）
    // ========================================

    @Nested
    @DisplayName("processAlertIfNeeded")
    class ProcessAlertIfNeeded {

        @Test
        @DisplayName("Issue #2715 ロットB: 送信者 locale が en の場合、アラート件名・本文が英語で組み立てられプレースホルダが残らない")
        void 送信者ロケールがenなら英語件名本文になる() {
            var realMessageSource = new org.springframework.context.support.ResourceBundleMessageSource();
            realMessageSource.setBasename("messages");
            realMessageSource.setDefaultEncoding("UTF-8");
            org.springframework.test.util.ReflectionTestUtils.setField(
                    batchService, "messageSource", realMessageSource);

            UserEntity creator = mock(UserEntity.class);
            given(creator.getId()).willReturn(7L);
            given(userLocaleCache.getLocale(7L)).willReturn("en");

            ConfirmableNotificationEntity notification = mock(ConfirmableNotificationEntity.class);
            given(notification.getId()).willReturn(100L);
            given(notification.getCreatedBy()).willReturn(creator);
            given(notification.getScopeType()).willReturn(ScopeType.TEAM);
            given(notification.getScopeId()).willReturn(10L);
            given(notification.getTitle()).willReturn("テスト確認通知");
            given(notification.getActionUrl()).willReturn(null);

            given(settingsRepository.findByScopeTypeAndScopeId(any(), any())).willReturn(java.util.Optional.empty());
            given(recipientRepository.countByConfirmableNotificationIdAndIsConfirmedTrue(100L)).willReturn(1L);
            given(recipientRepository.countByConfirmableNotificationIdAndExcludedAtIsNull(100L)).willReturn(2L);

            boolean alerted = batchService.processAlertIfNeeded(notification);

            assertThat(alerted).isTrue();
            org.mockito.ArgumentCaptor<String> titleCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            org.mockito.ArgumentCaptor<String> bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(notificationHelper).notify(
                    eq(7L), eq("CONFIRMABLE_NOTIFICATION_SENDER_ALERT"), any(NotificationPriority.class),
                    titleCaptor.capture(), bodyCaptor.capture(), anyString(), eq(100L),
                    any(NotificationScopeType.class), eq(10L), any(), any());
            assertThat(titleCaptor.getValue()).isEqualTo("Confirmation notification alert");
            assertThat(bodyCaptor.getValue()).doesNotContain("{0}").doesNotContain("{1}").doesNotContain("{2}")
                    .contains("50").contains("テスト確認通知");
        }
    }
}
