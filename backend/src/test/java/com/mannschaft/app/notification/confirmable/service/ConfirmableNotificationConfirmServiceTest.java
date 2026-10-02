package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationDeliveryStatus;
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
 * <p>CMP-260920-1040 是正6: §8.2・§9.2・§11.1 で confirm/confirmByToken/cancel が
 * {@code findByIdForUpdate}・{@code findByNotificationIdAndUserIdForUpdate}・
 * {@code findByConfirmTokenForUpdate} を呼ぶ実装へ変わったため、モックの呼び出し方を
 * 実装に合わせて更新する（旧: {@code findById}・{@code findByConfirmableNotificationId} の
 * 全件ロード前提のまま期待値だけ弱めることはしない）。完了判定は
 * {@code unconfirmedCount == 0 && deliveryStatus == DELIVERED && totalRecipientCount > 0}
 * という実エンティティの状態で検証し、モックのブール値では表現しない。</p>
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

    private ConfirmableNotificationEntity createActiveNotification(
            int totalRecipientCount, int unconfirmedCount, ConfirmableNotificationDeliveryStatus deliveryStatus) {
        return ConfirmableNotificationEntity.builder()
                .scopeType(ScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .title("テスト確認通知")
                .priority(ConfirmableNotificationPriority.NORMAL)
                .status(ConfirmableNotificationStatus.ACTIVE)
                .totalRecipientCount(totalRecipientCount)
                .unconfirmedCount(unconfirmedCount)
                .deliveryStatus(deliveryStatus)
                .build();
    }

    private ConfirmableNotificationEntity createCancelledNotification() {
        ConfirmableNotificationEntity notification =
                createActiveNotification(3, 3, ConfirmableNotificationDeliveryStatus.DELIVERED);
        notification.cancel(null);
        return notification;
    }

    private ConfirmableNotificationRecipientEntity createRecipient(
            ConfirmableNotificationEntity notification, Long userId, boolean confirmed) {
        UserEntity user = mock(UserEntity.class);
        given(user.getId()).willReturn(userId);

        return ConfirmableNotificationRecipientEntity.builder()
                .confirmableNotification(notification)
                .user(user)
                .confirmToken(java.util.UUID.randomUUID().toString())
                .isConfirmed(confirmed)
                .build();
    }

    // ========================================
    // confirm
    // ========================================

    @Nested
    @DisplayName("confirm")
    class Confirm {

        @Test
        @DisplayName("confirm_正常系_未確認のrecipientをconfirmするとisConfirmedがtrueになる"
                + "（findByIdForUpdate・findByNotificationIdAndUserIdForUpdateを使う実装に合わせる）")
        void confirm_正常系_未確認のrecipientをconfirmするとisConfirmedがtrueになる() {
            // given: 配信中（DELIVERING）のため、この1件を確認しても完了しない。
            ConfirmableNotificationEntity notification =
                    createActiveNotification(2, 2, ConfirmableNotificationDeliveryStatus.DELIVERING);
            ConfirmableNotificationRecipientEntity recipient1 = createRecipient(notification, USER_ID_1, false);

            given(notificationRepository.findByIdForUpdate(NOTIFICATION_ID))
                    .willReturn(Optional.of(notification));
            given(recipientRepository.findByNotificationIdAndUserIdForUpdate(NOTIFICATION_ID, USER_ID_1))
                    .willReturn(Optional.of(recipient1));

            // when
            confirmService.confirm(NOTIFICATION_ID, USER_ID_1);

            // then
            assertThat(recipient1.getIsConfirmed()).isTrue();
            assertThat(notification.getUnconfirmedCount()).isEqualTo(1);
            verify(recipientRepository).save(recipient1);
            verify(notificationRepository).save(notification);
        }

        @Test
        @DisplayName("confirm_全員確認済み時にCOMPLETED_配信完了(DELIVERED)で最後の1人を確認するとCOMPLETEDになる")
        void confirm_全員確認済み時にCOMPLETED_全recipientが確認済みになったらnotification_completeが呼ばれる() {
            // given: 全員配信完了（DELIVERED）で、あと1人（USER_ID_1）だけ未確認。
            ConfirmableNotificationEntity notification =
                    createActiveNotification(2, 1, ConfirmableNotificationDeliveryStatus.DELIVERED);
            ConfirmableNotificationRecipientEntity recipient1 = createRecipient(notification, USER_ID_1, false);

            given(notificationRepository.findByIdForUpdate(NOTIFICATION_ID))
                    .willReturn(Optional.of(notification));
            given(recipientRepository.findByNotificationIdAndUserIdForUpdate(NOTIFICATION_ID, USER_ID_1))
                    .willReturn(Optional.of(recipient1));

            // when
            confirmService.confirm(NOTIFICATION_ID, USER_ID_1);

            // then
            verify(notificationRepository).save(notification);
            assertThat(notification.getUnconfirmedCount()).isZero();
            assertThat(notification.getStatus()).isEqualTo(ConfirmableNotificationStatus.COMPLETED);
        }

        @Test
        @DisplayName("confirm_配信中はCOMPLETEDにしない_unconfirmedCountが0でもDELIVERING中は完了判定を保留する（§8.2・AC-40）")
        void confirm_配信中はCOMPLETEDにしない() {
            // given: このチャンクの受信者は全員確認済みになるが、他チャンクの配信がまだ続いている
            // （deliveryStatus=DELIVERING）ため、isReadyToCompleteはfalseになるべき。
            ConfirmableNotificationEntity notification =
                    createActiveNotification(2, 1, ConfirmableNotificationDeliveryStatus.DELIVERING);
            ConfirmableNotificationRecipientEntity recipient1 = createRecipient(notification, USER_ID_1, false);

            given(notificationRepository.findByIdForUpdate(NOTIFICATION_ID))
                    .willReturn(Optional.of(notification));
            given(recipientRepository.findByNotificationIdAndUserIdForUpdate(NOTIFICATION_ID, USER_ID_1))
                    .willReturn(Optional.of(recipient1));

            confirmService.confirm(NOTIFICATION_ID, USER_ID_1);

            assertThat(notification.getUnconfirmedCount()).isZero();
            assertThat(notification.getStatus())
                    .as("AC-40: DELIVERING中は全員確認してもCOMPLETEDにしない")
                    .isEqualTo(ConfirmableNotificationStatus.ACTIVE);
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

        @Test
        @DisplayName("confirmByToken_正常系_手順1(不変列読み取り)→手順2(親ロック)→手順3(受信者行の再ロック)の順で呼ばれる")
        void confirmByToken_正常系_親と受信者を再ロックして確認する() {
            String token = java.util.UUID.randomUUID().toString();
            ConfirmableNotificationEntity notification =
                    createActiveNotification(1, 1, ConfirmableNotificationDeliveryStatus.DELIVERED);
            ConfirmableNotificationRecipientEntity unlockedRecipient = createRecipient(notification, USER_ID_2, false);
            ConfirmableNotificationRecipientEntity lockedRecipient = createRecipient(notification, USER_ID_2, false);

            given(recipientRepository.findByConfirmToken(token)).willReturn(Optional.of(unlockedRecipient));
            given(notificationRepository.findByIdForUpdate(any())).willReturn(Optional.of(notification));
            given(recipientRepository.findByConfirmTokenForUpdate(token)).willReturn(Optional.of(lockedRecipient));

            confirmService.confirmByToken(token);

            assertThat(lockedRecipient.getIsConfirmed())
                    .as("§10.1: 確定はロック済みの受信者行（手順3で再取得したもの）に対して行う")
                    .isTrue();
            assertThat(notification.getStatus()).isEqualTo(ConfirmableNotificationStatus.COMPLETED);
        }
    }

    // ========================================
    // cancel
    // ========================================

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @Test
        @DisplayName("cancel_正常系_ADMINがcancelを呼ぶとstatusがCANCELLEDになる"
                + "（§10.2: findByIdForUpdateで親をロックしてから再判定する）")
        void cancel_正常系_ADMINがcancelを呼ぶとstatusがCANCELLEDになる() {
            // given
            ConfirmableNotificationEntity notification =
                    createActiveNotification(3, 3, ConfirmableNotificationDeliveryStatus.DELIVERED);
            UserEntity cancelUser = mock(UserEntity.class);
            given(cancelUser.getId()).willReturn(USER_ID_1);

            given(notificationRepository.findByIdForUpdate(NOTIFICATION_ID))
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
        @DisplayName("cancel_既キャンセル済み_すでにCANCELLEDな通知をcancelするとALREADY_CANCELLEDエラーがthrowされる"
                + "（§10.2: ロック取得後の再判定でACTIVE以外を拒否する）")
        void cancel_既キャンセル済み_すでにCANCELLEDな通知をcancelするとALREADY_CANCELLEDエラーがthrowされる() {
            // given
            ConfirmableNotificationEntity cancelledNotification = createCancelledNotification();

            given(notificationRepository.findByIdForUpdate(NOTIFICATION_ID))
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
