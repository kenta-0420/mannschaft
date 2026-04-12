package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationSettingsEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.entity.ConfirmedVia;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import com.mannschaft.app.notification.confirmable.event.ConfirmableNotificationCreatedEvent;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.service.NotificationHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ConfirmableNotificationService} の単体テスト。
 * 確認通知の送信・確認・キャンセルのビジネスロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConfirmableNotificationService 単体テスト")
class ConfirmableNotificationServiceTest {

    @Mock
    private ConfirmableNotificationRepository notificationRepository;

    @Mock
    private ConfirmableNotificationRecipientRepository recipientRepository;

    @Mock
    private ConfirmableNotificationSettingsService settingsService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationHelper notificationHelper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ConfirmableNotificationService notificationService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SCOPE_ID = 10L;
    private static final Long NOTIFICATION_ID = 100L;
    private static final Long USER_ID_1 = 1L;
    private static final Long USER_ID_2 = 2L;
    private static final Long USER_ID_3 = 3L;

    private ConfirmableNotificationEntity createActiveNotification() {
        return ConfirmableNotificationEntity.builder()
                .scopeType(ScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .title("テスト確認通知")
                .priority(ConfirmableNotificationPriority.NORMAL)
                .totalRecipientCount(3)
                .build();
    }

    private ConfirmableNotificationEntity createCancelledNotification() {
        ConfirmableNotificationEntity notification = createActiveNotification();
        notification.cancel(null);
        return notification;
    }

    private ConfirmableNotificationSettingsEntity createSettings(
            Integer firstReminderMinutes, Integer secondReminderMinutes) {
        return ConfirmableNotificationSettingsEntity.builder()
                .scopeType(ScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .defaultFirstReminderMinutes(firstReminderMinutes)
                .defaultSecondReminderMinutes(secondReminderMinutes)
                .build();
    }

    /**
     * IDを持つ受信者モックを作成する（checkAndCompleteIfAllConfirmedで getId() が使われるため）。
     */
    private ConfirmableNotificationRecipientEntity createMockedRecipient(
            Long id, ConfirmableNotificationEntity notification, Long userId, boolean confirmed) {
        UserEntity user = mock(UserEntity.class);
        given(user.getId()).willReturn(userId);

        ConfirmableNotificationRecipientEntity recipient =
                mock(ConfirmableNotificationRecipientEntity.class);
        given(recipient.getId()).willReturn(id);
        given(recipient.getUser()).willReturn(user);
        given(recipient.getIsConfirmed()).willReturn(confirmed);
        given(recipient.isExcluded()).willReturn(false);
        return recipient;
    }

    // ========================================
    // send
    // ========================================

    @Nested
    @DisplayName("send")
    class Send {

        @Test
        @DisplayName("send_正常系_受信者3名でsendを呼ぶとnotificationと3件のrecipientが作成される")
        void send_正常系_受信者3名でsendを呼ぶとnotificationと3件のrecipientが作成される() {
            // given
            List<Long> recipientIds = List.of(USER_ID_1, USER_ID_2, USER_ID_3);
            ConfirmableNotificationSettingsEntity settings = createSettings(null, null);
            ConfirmableNotificationEntity savedNotification = createActiveNotification();

            given(settingsService.getOrCreate(ScopeType.TEAM, SCOPE_ID)).willReturn(settings);
            given(userRepository.findById(USER_ID_1)).willReturn(Optional.empty());
            given(userRepository.getReferenceById(USER_ID_1)).willReturn(mock(UserEntity.class));
            given(userRepository.getReferenceById(USER_ID_2)).willReturn(mock(UserEntity.class));
            given(userRepository.getReferenceById(USER_ID_3)).willReturn(mock(UserEntity.class));
            given(notificationRepository.save(any(ConfirmableNotificationEntity.class)))
                    .willReturn(savedNotification);
            given(recipientRepository.saveAll(any()))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            ConfirmableNotificationEntity result = notificationService.send(
                    ScopeType.TEAM, SCOPE_ID, "テスト通知", null,
                    ConfirmableNotificationPriority.NORMAL, null,
                    null, null, null, null, USER_ID_1, recipientIds);

            // then
            assertThat(result).isNotNull();
            verify(notificationRepository).save(any(ConfirmableNotificationEntity.class));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ConfirmableNotificationRecipientEntity>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(recipientRepository).saveAll(captor.capture());
            assertThat(captor.getValue()).hasSize(3);

            verify(eventPublisher).publishEvent(any(ConfirmableNotificationCreatedEvent.class));
        }

        @Test
        @DisplayName("send_リマインド分数フォールバック_個別設定なしスコープ設定あり_スコープ設定が使われる")
        void send_リマインド分数フォールバック_個別設定なしスコープ設定あり_スコープ設定が使われる() {
            // given
            List<Long> recipientIds = List.of(USER_ID_1);
            // スコープ設定に 60/30 分を設定
            ConfirmableNotificationSettingsEntity settings = createSettings(60, 30);
            ConfirmableNotificationEntity savedNotification = createActiveNotification();

            given(settingsService.getOrCreate(ScopeType.TEAM, SCOPE_ID)).willReturn(settings);
            given(userRepository.findById(USER_ID_1)).willReturn(Optional.empty());
            given(userRepository.getReferenceById(USER_ID_1)).willReturn(mock(UserEntity.class));
            given(notificationRepository.save(any(ConfirmableNotificationEntity.class)))
                    .willReturn(savedNotification);
            given(recipientRepository.saveAll(any()))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            notificationService.send(
                    ScopeType.TEAM, SCOPE_ID, "テスト通知", null,
                    ConfirmableNotificationPriority.NORMAL, null,
                    null, null, null, null, USER_ID_1, recipientIds);

            // then: saveAll に渡されたrecipientのresolvedFirstReminderMinutesがスコープ設定の60分
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ConfirmableNotificationRecipientEntity>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(recipientRepository).saveAll(captor.capture());
            List<ConfirmableNotificationRecipientEntity> recipients = captor.getValue();
            assertThat(recipients).hasSize(1);
            assertThat(recipients.get(0).getResolvedFirstReminderMinutes()).isEqualTo(60);
            assertThat(recipients.get(0).getResolvedSecondReminderMinutes()).isEqualTo(30);
        }
    }

    // ========================================
    // confirm
    // ========================================

    @Nested
    @DisplayName("confirm")
    class Confirm {

        @Test
        @DisplayName("confirm_正常系_未確認のrecipientをconfirmするとisConfirmedがtrueになる")
        void confirm_正常系_未確認のrecipientをconfirmするとisConfirmedがtrueになる() {
            // given
            ConfirmableNotificationEntity notification = createActiveNotification();

            // 受信者1: USER_ID_1（これからconfirmする対象）
            ConfirmableNotificationRecipientEntity recipient1 =
                    createMockedRecipient(1L, notification, USER_ID_1, false);

            // 受信者2: USER_ID_2（まだ未確認）
            ConfirmableNotificationRecipientEntity recipient2 =
                    createMockedRecipient(2L, notification, USER_ID_2, false);

            given(notificationRepository.findById(NOTIFICATION_ID))
                    .willReturn(Optional.of(notification));
            given(recipientRepository.findByConfirmableNotificationId(NOTIFICATION_ID))
                    .willReturn(List.of(recipient1, recipient2));
            given(recipientRepository.save(any())).willReturn(recipient1);

            // when
            notificationService.confirm(NOTIFICATION_ID, USER_ID_1);

            // then: confirm(APP) が呼ばれ、save される
            verify(recipient1).confirm(ConfirmedVia.APP);
            verify(recipientRepository).save(recipient1);
        }

        @Test
        @DisplayName("confirm_全員確認済み時にCOMPLETED_全recipientが確認済みになったらnotification_completeが呼ばれる")
        void confirm_全員確認済み時にCOMPLETED_全recipientが確認済みになったらnotification_completeが呼ばれる() {
            // given
            ConfirmableNotificationEntity notification = createActiveNotification();

            // recipient1: USER_ID_1（これからconfirmする対象）
            ConfirmableNotificationRecipientEntity recipient1 =
                    createMockedRecipient(1L, notification, USER_ID_1, false);

            // recipient2: USER_ID_2（確認済み済み）
            ConfirmableNotificationRecipientEntity recipient2 =
                    createMockedRecipient(2L, notification, USER_ID_2, true);

            given(notificationRepository.findById(NOTIFICATION_ID))
                    .willReturn(Optional.of(notification));
            given(recipientRepository.findByConfirmableNotificationId(NOTIFICATION_ID))
                    .willReturn(List.of(recipient1, recipient2));
            given(recipientRepository.save(any())).willReturn(recipient1);
            given(notificationRepository.save(any())).willReturn(notification);

            // when
            notificationService.confirm(NOTIFICATION_ID, USER_ID_1);

            // then: 全員確認済みになったのでnotificationが complete() される
            verify(notificationRepository).save(notification);
            // notification.complete() が呼ばれると status が COMPLETED になる
            // (モックではなく実体エンティティなので直接 status を確認できる)
            assertThat(notification.getStatus()).isEqualTo(ConfirmableNotificationStatus.COMPLETED);
        }
    }

    // ========================================
    // confirmByToken
    // ========================================

    @Nested
    @DisplayName("confirmByToken")
    class ConfirmByToken {

        @Test
        @DisplayName("confirmByToken_無効トークン_存在しないtokenでconfirmByTokenを呼ぶとINVALID_TOKENエラーがthrowされる")
        void confirmByToken_無効トークン_存在しないtokenでconfirmByTokenを呼ぶとINVALID_TOKENエラーがthrowされる() {
            // given
            String invalidToken = "invalid-uuid-token";
            given(recipientRepository.findByConfirmToken(invalidToken))
                    .willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> notificationService.confirmByToken(invalidToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo(ConfirmableNotificationErrorCode.INVALID_TOKEN.getCode()));
        }
    }

    // ========================================
    // cancel
    // ========================================

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @Test
        @DisplayName("cancel_正常系_ADMINがcancelを呼ぶとstatusがCANCELLEDになる")
        void cancel_正常系_ADMINがcancelを呼ぶとstatusがCANCELLEDになる() {
            // given
            ConfirmableNotificationEntity notification = createActiveNotification();
            UserEntity cancelUser = mock(UserEntity.class);
            given(cancelUser.getId()).willReturn(USER_ID_1);

            given(notificationRepository.findById(NOTIFICATION_ID))
                    .willReturn(Optional.of(notification));
            given(userRepository.findById(USER_ID_1)).willReturn(Optional.of(cancelUser));
            given(notificationRepository.save(any())).willReturn(notification);

            // when
            notificationService.cancel(NOTIFICATION_ID, USER_ID_1);

            // then
            assertThat(notification.getStatus()).isEqualTo(ConfirmableNotificationStatus.CANCELLED);
            verify(notificationRepository).save(notification);
        }

        @Test
        @DisplayName("cancel_既キャンセル済み_すでにCANCELLEDな通知をcancelするとALREADY_CANCELLEDエラーがthrowされる")
        void cancel_既キャンセル済み_すでにCANCELLEDな通知をcancelするとALREADY_CANCELLEDエラーがthrowされる() {
            // given
            ConfirmableNotificationEntity cancelledNotification = createCancelledNotification();

            given(notificationRepository.findById(NOTIFICATION_ID))
                    .willReturn(Optional.of(cancelledNotification));

            // when / then
            assertThatThrownBy(() -> notificationService.cancel(NOTIFICATION_ID, USER_ID_1))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo(ConfirmableNotificationErrorCode.ALREADY_CANCELLED.getCode()));

            verify(notificationRepository, never()).save(any());
        }
    }
}
