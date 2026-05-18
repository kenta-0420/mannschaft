package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.entity.ConfirmedVia;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.service.NotificationHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ConfirmableNotificationConfirmService} の単体テスト。
 *
 * <p>リファクタリング第9弾でファサード {@code ConfirmableNotificationService} から
 * 分離された確認・キャンセル・リマインド再送のロジックを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConfirmableNotificationConfirmService 単体テスト")
class ConfirmableNotificationConfirmServiceTest {

    @Mock
    private ConfirmableNotificationRepository notificationRepository;

    @Mock
    private ConfirmableNotificationRecipientRepository recipientRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationHelper notificationHelper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ConfirmableNotificationConfirmService confirmService;

    private static final Long SCOPE_ID = 10L;
    private static final Long NOTIFICATION_ID = 100L;
    private static final Long USER_ID_1 = 1L;
    private static final Long USER_ID_2 = 2L;

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

            ConfirmableNotificationRecipientEntity recipient1 =
                    createMockedRecipient(1L, notification, USER_ID_1, false);
            ConfirmableNotificationRecipientEntity recipient2 =
                    createMockedRecipient(2L, notification, USER_ID_2, false);

            given(notificationRepository.findById(NOTIFICATION_ID))
                    .willReturn(Optional.of(notification));
            given(recipientRepository.findByConfirmableNotificationId(NOTIFICATION_ID))
                    .willReturn(List.of(recipient1, recipient2));
            given(recipientRepository.save(any())).willReturn(recipient1);

            // when
            confirmService.confirm(NOTIFICATION_ID, USER_ID_1);

            // then
            verify(recipient1).confirm(ConfirmedVia.APP);
            verify(recipientRepository).save(recipient1);
        }

        @Test
        @DisplayName("confirm_全員確認済み時にCOMPLETED_全recipientが確認済みになったらnotification_completeが呼ばれる")
        void confirm_全員確認済み時にCOMPLETED_全recipientが確認済みになったらnotification_completeが呼ばれる() {
            // given
            ConfirmableNotificationEntity notification = createActiveNotification();

            ConfirmableNotificationRecipientEntity recipient1 =
                    createMockedRecipient(1L, notification, USER_ID_1, false);
            ConfirmableNotificationRecipientEntity recipient2 =
                    createMockedRecipient(2L, notification, USER_ID_2, true);

            given(notificationRepository.findById(NOTIFICATION_ID))
                    .willReturn(Optional.of(notification));
            given(recipientRepository.findByConfirmableNotificationId(NOTIFICATION_ID))
                    .willReturn(List.of(recipient1, recipient2));
            given(recipientRepository.save(any())).willReturn(recipient1);
            given(notificationRepository.save(any())).willReturn(notification);

            // when
            confirmService.confirm(NOTIFICATION_ID, USER_ID_1);

            // then
            verify(notificationRepository).save(notification);
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
            assertThatThrownBy(() -> confirmService.confirmByToken(invalidToken))
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
            confirmService.cancel(NOTIFICATION_ID, USER_ID_1);

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
            assertThatThrownBy(() -> confirmService.cancel(NOTIFICATION_ID, USER_ID_1))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo(ConfirmableNotificationErrorCode.ALREADY_CANCELLED.getCode()));

            verify(notificationRepository, never()).save(any());
        }
    }
}
