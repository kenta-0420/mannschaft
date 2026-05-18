package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.UnconfirmedVisibility;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * {@link ConfirmableNotificationQueryService} の単体テスト。
 *
 * <p>リファクタリング第9弾でファサード {@code ConfirmableNotificationService} から
 * 分離された参照系処理（詳細・受信者一覧・MEMBER 視点の認可判定）を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConfirmableNotificationQueryService 単体テスト")
class ConfirmableNotificationQueryServiceTest {

    @Mock
    private ConfirmableNotificationRepository notificationRepository;

    @Mock
    private ConfirmableNotificationRecipientRepository recipientRepository;

    @InjectMocks
    private ConfirmableNotificationQueryService queryService;

    private static final Long SCOPE_ID = 10L;
    private static final Long NOTIFICATION_ID = 100L;
    private static final Long USER_ID_1 = 1L;
    private static final Long USER_ID_2 = 2L;
    private static final Long USER_ID_3 = 3L;

    /**
     * IDを持つ受信者モックを作成する。
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
    // F04.9 Phase D: 未確認者一覧の可視化（unconfirmedVisibility）
    // ========================================

    /**
     * 認可テスト 6 ケース（公開範囲 3 × ロール 2）。
     *
     * <ul>
     *   <li>HIDDEN × ADMIN+ → 全件返す（既存挙動）</li>
     *   <li>HIDDEN × MEMBER → 403</li>
     *   <li>CREATOR_AND_ADMIN × ADMIN+ → 全件返す（既存挙動）</li>
     *   <li>CREATOR_AND_ADMIN × MEMBER → 403</li>
     *   <li>ALL_MEMBERS × ADMIN+ → 全件返す</li>
     *   <li>ALL_MEMBERS × MEMBER（受信者本人） → 未確認者のみ返す</li>
     * </ul>
     */
    @Nested
    @DisplayName("unconfirmedVisibility 認可")
    class UnconfirmedVisibilityAuthorization {

        /** 指定の公開範囲を持つ ACTIVE 通知を生成する */
        private ConfirmableNotificationEntity notificationWithVisibility(UnconfirmedVisibility visibility) {
            return ConfirmableNotificationEntity.builder()
                    .scopeType(ScopeType.TEAM)
                    .scopeId(SCOPE_ID)
                    .title("テスト確認通知")
                    .priority(ConfirmableNotificationPriority.NORMAL)
                    .totalRecipientCount(3)
                    .unconfirmedVisibility(visibility)
                    .build();
        }

        // -------------------------------------------------------------------
        // ADMIN+ 経路 — getRecipients() は公開範囲に関係なく全件返す
        // -------------------------------------------------------------------

        @Test
        @DisplayName("getRecipients_HIDDEN_ADMINが呼ぶと全件返る")
        void getRecipients_HIDDEN_ADMINが呼ぶと全件返る() {
            ConfirmableNotificationEntity notification = notificationWithVisibility(UnconfirmedVisibility.HIDDEN);
            ConfirmableNotificationRecipientEntity r1 = createMockedRecipient(1L, notification, USER_ID_1, false);
            ConfirmableNotificationRecipientEntity r2 = createMockedRecipient(2L, notification, USER_ID_2, true);

            given(notificationRepository.existsById(NOTIFICATION_ID)).willReturn(true);
            given(recipientRepository.findByConfirmableNotificationId(NOTIFICATION_ID))
                    .willReturn(List.of(r1, r2));

            List<ConfirmableNotificationRecipientEntity> result =
                    queryService.getRecipients(NOTIFICATION_ID);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("getRecipients_CREATOR_AND_ADMIN_ADMINが呼ぶと全件返る")
        void getRecipients_CREATOR_AND_ADMIN_ADMINが呼ぶと全件返る() {
            ConfirmableNotificationEntity notification = notificationWithVisibility(UnconfirmedVisibility.CREATOR_AND_ADMIN);
            ConfirmableNotificationRecipientEntity r1 = createMockedRecipient(1L, notification, USER_ID_1, false);
            ConfirmableNotificationRecipientEntity r2 = createMockedRecipient(2L, notification, USER_ID_2, true);

            given(notificationRepository.existsById(NOTIFICATION_ID)).willReturn(true);
            given(recipientRepository.findByConfirmableNotificationId(NOTIFICATION_ID))
                    .willReturn(List.of(r1, r2));

            List<ConfirmableNotificationRecipientEntity> result =
                    queryService.getRecipients(NOTIFICATION_ID);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("getRecipients_ALL_MEMBERS_ADMINが呼ぶと全件返る")
        void getRecipients_ALL_MEMBERS_ADMINが呼ぶと全件返る() {
            ConfirmableNotificationEntity notification = notificationWithVisibility(UnconfirmedVisibility.ALL_MEMBERS);
            ConfirmableNotificationRecipientEntity r1 = createMockedRecipient(1L, notification, USER_ID_1, false);
            ConfirmableNotificationRecipientEntity r2 = createMockedRecipient(2L, notification, USER_ID_2, true);

            given(notificationRepository.existsById(NOTIFICATION_ID)).willReturn(true);
            given(recipientRepository.findByConfirmableNotificationId(NOTIFICATION_ID))
                    .willReturn(List.of(r1, r2));

            List<ConfirmableNotificationRecipientEntity> result =
                    queryService.getRecipients(NOTIFICATION_ID);

            assertThat(result).hasSize(2);
        }

        // -------------------------------------------------------------------
        // MEMBER 経路 — getRecipientsForMember() で公開範囲・受信者本人を判定
        // -------------------------------------------------------------------

        @Test
        @DisplayName("getRecipientsForMember_HIDDEN_MEMBERが呼ぶと403_COMMON_002エラー")
        void getRecipientsForMember_HIDDEN_MEMBERが呼ぶと403() {
            ConfirmableNotificationEntity notification = notificationWithVisibility(UnconfirmedVisibility.HIDDEN);
            given(notificationRepository.findById(NOTIFICATION_ID)).willReturn(Optional.of(notification));

            assertThatThrownBy(() -> queryService.getRecipientsForMember(NOTIFICATION_ID, USER_ID_1))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo(CommonErrorCode.COMMON_002.getCode()));
        }

        @Test
        @DisplayName("getRecipientsForMember_CREATOR_AND_ADMIN_MEMBERが呼ぶと403_COMMON_002エラー")
        void getRecipientsForMember_CREATOR_AND_ADMIN_MEMBERが呼ぶと403() {
            ConfirmableNotificationEntity notification = notificationWithVisibility(UnconfirmedVisibility.CREATOR_AND_ADMIN);
            given(notificationRepository.findById(NOTIFICATION_ID)).willReturn(Optional.of(notification));

            assertThatThrownBy(() -> queryService.getRecipientsForMember(NOTIFICATION_ID, USER_ID_1))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo(CommonErrorCode.COMMON_002.getCode()));
        }

        @Test
        @DisplayName("getRecipientsForMember_ALL_MEMBERS_受信者本人なら未確認者のみ返る")
        void getRecipientsForMember_ALL_MEMBERS_受信者本人なら未確認者のみ返る() {
            // given: ALL_MEMBERS 公開・受信者3名（USER_ID_1=未確認・USER_ID_2=確認済・USER_ID_3=未確認）
            ConfirmableNotificationEntity notification = notificationWithVisibility(UnconfirmedVisibility.ALL_MEMBERS);
            ConfirmableNotificationRecipientEntity r1 = createMockedRecipient(1L, notification, USER_ID_1, false);
            ConfirmableNotificationRecipientEntity r2 = createMockedRecipient(2L, notification, USER_ID_2, true);
            ConfirmableNotificationRecipientEntity r3 = createMockedRecipient(3L, notification, USER_ID_3, false);

            given(notificationRepository.findById(NOTIFICATION_ID)).willReturn(Optional.of(notification));
            given(recipientRepository.findByConfirmableNotificationId(NOTIFICATION_ID))
                    .willReturn(List.of(r1, r2, r3));

            // when: USER_ID_1（受信者本人・未確認）が呼ぶ
            List<ConfirmableNotificationRecipientEntity> result =
                    queryService.getRecipientsForMember(NOTIFICATION_ID, USER_ID_1);

            // then: 未確認者2名のみ返る（USER_ID_2 は確認済みなので除外）
            assertThat(result).hasSize(2);
            assertThat(result).extracting(r -> r.getUser().getId())
                    .containsExactlyInAnyOrder(USER_ID_1, USER_ID_3);
        }

        @Test
        @DisplayName("getRecipientsForMember_ALL_MEMBERS_受信者でないユーザーが呼ぶと403_COMMON_002エラー")
        void getRecipientsForMember_ALL_MEMBERS_非受信者が呼ぶと403() {
            // given: ALL_MEMBERS 公開だが、呼び出しユーザーは受信者ではない
            ConfirmableNotificationEntity notification = notificationWithVisibility(UnconfirmedVisibility.ALL_MEMBERS);
            ConfirmableNotificationRecipientEntity r1 = createMockedRecipient(1L, notification, USER_ID_2, false);

            given(notificationRepository.findById(NOTIFICATION_ID)).willReturn(Optional.of(notification));
            given(recipientRepository.findByConfirmableNotificationId(NOTIFICATION_ID))
                    .willReturn(List.of(r1));

            // when / then: 非受信者の USER_ID_1 が呼ぶと 403
            assertThatThrownBy(() -> queryService.getRecipientsForMember(NOTIFICATION_ID, USER_ID_1))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo(CommonErrorCode.COMMON_002.getCode()));
        }
    }
}
