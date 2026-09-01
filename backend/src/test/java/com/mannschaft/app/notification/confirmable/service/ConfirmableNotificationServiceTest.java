package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationSettingsEntity;
import com.mannschaft.app.notification.confirmable.entity.UnconfirmedVisibility;
import com.mannschaft.app.notification.confirmable.event.ConfirmableNotificationCreatedEvent;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.credit.service.NotificationCreditService;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ConfirmableNotificationService} の単体テスト。
 *
 * <p>リファクタリング第9弾でファサード分割した結果、本テストは
 * ファサードが直接実装している {@code send} 系のみを対象にする。
 * 確認系（confirm/cancel/resendReminder）は
 * {@link ConfirmableNotificationConfirmServiceTest}、
 * 参照系（getDetail/getRecipients/listByScope 等）は
 * {@link ConfirmableNotificationQueryServiceTest} で検証する。</p>
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

    @Mock
    private NotificationCreditService notificationCreditService;

    @Mock
    private ConfirmableNotificationConfirmService confirmService;

    @Mock
    private ConfirmableNotificationQueryService queryService;

    @InjectMocks
    private ConfirmableNotificationService notificationService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SCOPE_ID = 10L;
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

    private ConfirmableNotificationSettingsEntity createSettings(
            Integer firstReminderMinutes, Integer secondReminderMinutes) {
        return ConfirmableNotificationSettingsEntity.builder()
                .scopeType(ScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .defaultFirstReminderMinutes(firstReminderMinutes)
                .defaultSecondReminderMinutes(secondReminderMinutes)
                .build();
    }

    // ========================================
    // send
    // ========================================

    @Nested
    @DisplayName("send")
    class Send {

        @Test
        @DisplayName("PLATFORM確認通知は組織通知クレジットを消費しない")
        void send_platform_doesNotConsumeOrganizationCredit() {
            List<Long> recipientIds = List.of(USER_ID_1);
            ConfirmableNotificationSettingsEntity settings = ConfirmableNotificationSettingsEntity.builder()
                    .scopeType(ScopeType.PLATFORM)
                    .scopeId(USER_ID_1)
                    .build();
            given(settingsService.getOrCreate(ScopeType.PLATFORM, USER_ID_1)).willReturn(settings);
            given(userRepository.findById(USER_ID_1)).willReturn(Optional.empty());
            given(userRepository.getReferenceById(USER_ID_1)).willReturn(mock(UserEntity.class));
            given(notificationRepository.save(any(ConfirmableNotificationEntity.class)))
                    .willReturn(createActiveNotification());
            given(recipientRepository.saveAll(any()))
                    .willAnswer(invocation -> invocation.getArgument(0));

            notificationService.send(
                    ScopeType.PLATFORM, USER_ID_1, "個人札の最終認証", null,
                    ConfirmableNotificationPriority.HIGH, null,
                    null, null, null, null, null, USER_ID_1, recipientIds);

            verify(notificationCreditService, never()).consume(any(), anyInt(), any());
        }

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
                    null, null, null, null, null, USER_ID_1, recipientIds);

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
                    null, null, null, null, null, USER_ID_1, recipientIds);

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
    // F04.9 Phase D: send() の unconfirmedVisibility フォールバック
    // ========================================

    @Nested
    @DisplayName("send unconfirmedVisibility フォールバック")
    class SendUnconfirmedVisibilityFallback {

        @Test
        @DisplayName("send_unconfirmedVisibility省略_スコープ設定がCREATOR_AND_ADMIN_スコープ設定値が採用される")
        void send_unconfirmedVisibility省略時にスコープ設定値が採用される() {
            // given: スコープ設定の default を ALL_MEMBERS に設定
            List<Long> recipientIds = List.of(USER_ID_1);
            ConfirmableNotificationSettingsEntity settings = ConfirmableNotificationSettingsEntity.builder()
                    .scopeType(ScopeType.TEAM)
                    .scopeId(SCOPE_ID)
                    .defaultUnconfirmedVisibility(UnconfirmedVisibility.ALL_MEMBERS)
                    .build();

            given(settingsService.getOrCreate(ScopeType.TEAM, SCOPE_ID)).willReturn(settings);
            given(userRepository.findById(USER_ID_1)).willReturn(Optional.empty());
            given(userRepository.getReferenceById(USER_ID_1)).willReturn(mock(UserEntity.class));
            given(notificationRepository.save(any(ConfirmableNotificationEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(recipientRepository.saveAll(any()))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when: リクエストの unconfirmedVisibility は null（省略）
            ConfirmableNotificationEntity result = notificationService.send(
                    ScopeType.TEAM, SCOPE_ID, "テスト通知", null,
                    ConfirmableNotificationPriority.NORMAL, null,
                    null, null, null, null, null, USER_ID_1, recipientIds);

            // then: スコープ設定の ALL_MEMBERS が採用される
            ArgumentCaptor<ConfirmableNotificationEntity> captor =
                    ArgumentCaptor.forClass(ConfirmableNotificationEntity.class);
            verify(notificationRepository).save(captor.capture());
            assertThat(captor.getValue().getUnconfirmedVisibility())
                    .isEqualTo(UnconfirmedVisibility.ALL_MEMBERS);
        }

        @Test
        @DisplayName("send_unconfirmedVisibility指定あり_リクエスト値が優先される")
        void send_unconfirmedVisibility指定あり_リクエスト値が優先される() {
            // given: スコープ設定が CREATOR_AND_ADMIN だが、リクエストで HIDDEN を明示
            List<Long> recipientIds = List.of(USER_ID_1);
            ConfirmableNotificationSettingsEntity settings = ConfirmableNotificationSettingsEntity.builder()
                    .scopeType(ScopeType.TEAM)
                    .scopeId(SCOPE_ID)
                    .defaultUnconfirmedVisibility(UnconfirmedVisibility.CREATOR_AND_ADMIN)
                    .build();

            given(settingsService.getOrCreate(ScopeType.TEAM, SCOPE_ID)).willReturn(settings);
            given(userRepository.findById(USER_ID_1)).willReturn(Optional.empty());
            given(userRepository.getReferenceById(USER_ID_1)).willReturn(mock(UserEntity.class));
            given(notificationRepository.save(any(ConfirmableNotificationEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(recipientRepository.saveAll(any()))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when: リクエストで HIDDEN を明示
            notificationService.send(
                    ScopeType.TEAM, SCOPE_ID, "テスト通知", null,
                    ConfirmableNotificationPriority.NORMAL, null,
                    null, null, null, null, UnconfirmedVisibility.HIDDEN, USER_ID_1, recipientIds);

            // then: リクエスト値の HIDDEN が採用される
            ArgumentCaptor<ConfirmableNotificationEntity> captor =
                    ArgumentCaptor.forClass(ConfirmableNotificationEntity.class);
            verify(notificationRepository).save(captor.capture());
            assertThat(captor.getValue().getUnconfirmedVisibility())
                    .isEqualTo(UnconfirmedVisibility.HIDDEN);
        }
    }
}
