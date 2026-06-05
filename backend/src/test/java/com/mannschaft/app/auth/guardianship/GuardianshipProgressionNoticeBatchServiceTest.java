package com.mannschaft.app.auth.guardianship;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.ParentalConsentService;
import com.mannschaft.app.family.service.CareLinkService;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GuardianshipProgressionNoticeBatchService} の単体テスト（F08.9 P3c-3 進学予告）。
 *
 * <p>JP ポリシーで 2013-04-02 生まれの子の封印境界日は 2026-04-01。
 * 3ヶ月前ウィンドウ [2026-01-01, 2026-04-01) の内外を Clock 固定で検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GuardianshipProgressionNoticeBatchService")
class GuardianshipProgressionNoticeBatchServiceTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final Long GUARDIAN_ID = 100L;
    private static final Long CHILD_ID = 200L;
    /** JP: 2013-04-02 生まれ → 封印境界日 2026-04-01。 */
    private static final String CHILD_BIRTH = "2013-04-02";
    private static final LocalDate SEAL_DATE = LocalDate.of(2026, 4, 1);

    @Mock private ParentalConsentService parentalConsentService;
    @Mock private CareLinkService careLinkService;
    @Mock private UserRepository userRepository;
    @Mock private GuardianshipTransitionNotificationRepository transitionNotificationRepository;
    @Mock private NotificationHelper notificationHelper;
    @Mock private EmailOutboxService emailOutboxService;
    @Mock private MessageSource messageSource;

    private GuardianshipProgressionNoticeBatchService newService(LocalDate today) {
        Clock clock = Clock.fixed(today.atStartOfDay(JST).toInstant(), JST);
        // JP ポリシーを実体で使う（境界日算出の整合を実ロジックで検証）。
        GuardianshipAgePolicyRegistry registry = new GuardianshipAgePolicyRegistry(
                List.of(new JapanGuardianshipAgePolicy()), new DefaultGuardianshipAgePolicy());
        return new GuardianshipProgressionNoticeBatchService(
                parentalConsentService, careLinkService, userRepository, registry,
                transitionNotificationRepository, notificationHelper, emailOutboxService,
                messageSource, clock);
    }

    @BeforeEach
    void stubMessages() {
        lenient().when(messageSource.getMessage(eq("notification.guardianship.progression.title"),
                any(), any(), any(Locale.class))).thenReturn("予告タイトル");
        lenient().when(messageSource.getMessage(eq("notification.guardianship.progression.body"),
                any(), any(), any(Locale.class))).thenReturn("予告本文");
    }

    private UserEntity user(Long id, String birthDate, String email, String passwordHash) {
        UserEntity u = UserEntity.builder()
                .email(email)
                .lastName("山田").firstName("花子").displayName("はなこ")
                .locale("ja").timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .countryCode("JP")
                .birthDate(birthDate)
                .passwordHash(passwordHash)
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    @Test
    @DisplayName("3ヶ月前ちょうど（ウィンドウ開始日）は送信する")
    void sendsAtWindowStart() {
        UserEntity child = user(CHILD_ID, CHILD_BIRTH, "child@example.com", null);
        when(parentalConsentService.listApprovedParentChildPairs(0, 500))
                .thenReturn(List.of(new ParentalConsentService.ParentChildPair(GUARDIAN_ID, CHILD_ID)));
        when(careLinkService.listActiveParentWatcherPairs(0, 500)).thenReturn(List.of());
        when(userRepository.findByIdIn(any())).thenReturn(List.of(child));
        when(userRepository.findById(GUARDIAN_ID))
                .thenReturn(java.util.Optional.of(user(GUARDIAN_ID, "1980-01-01", "parent@example.com", "h")));
        when(transitionNotificationRepository
                .existsByNotificationKindAndRecipientUserIdAndChildUserIdAndSealDate(
                        any(), any(), any(), any())).thenReturn(false);

        // ウィンドウ開始 = 2026-01-01（2026-04-01 の3ヶ月前）
        newService(LocalDate.of(2026, 1, 1)).execute();

        verify(notificationHelper).notify(eq(GUARDIAN_ID), eq("GUARDIANSHIP_PROGRESSION_NOTICE"),
                any(), any(), eq("GUARDIANSHIP_PROGRESSION"), eq(CHILD_ID),
                eq(NotificationScopeType.PERSONAL), eq(GUARDIAN_ID), any(), isNull());
        ArgumentCaptor<GuardianshipTransitionNotificationEntity> rec =
                ArgumentCaptor.forClass(GuardianshipTransitionNotificationEntity.class);
        verify(transitionNotificationRepository).save(rec.capture());
        assertThat(rec.getValue().getSealDate()).isEqualTo(SEAL_DATE);
        assertThat(rec.getValue().getNotificationKind())
                .isEqualTo(GuardianshipTransitionNotificationKind.PROGRESSION_NOTICE);
        // メールも outbox に enqueue される（ルーティング可能な保護者メール）。
        verify(emailOutboxService).enqueue(any(EmailOutboxRequest.class));
    }

    @Test
    @DisplayName("ウィンドウ開始日の前日は送信しない")
    void noSendBeforeWindow() {
        UserEntity child = user(CHILD_ID, CHILD_BIRTH, "child@example.com", null);
        when(parentalConsentService.listApprovedParentChildPairs(0, 500))
                .thenReturn(List.of(new ParentalConsentService.ParentChildPair(GUARDIAN_ID, CHILD_ID)));
        when(careLinkService.listActiveParentWatcherPairs(0, 500)).thenReturn(List.of());
        when(userRepository.findByIdIn(any())).thenReturn(List.of(child));

        // 2025-12-31 = ウィンドウ開始 2026-01-01 の前日
        newService(LocalDate.of(2025, 12, 31)).execute();

        verify(notificationHelper, never()).notify(anyLong(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        verify(transitionNotificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("封印境界日当日は送信しない（ウィンドウは半開区間 [start, seal)）")
    void noSendOnSealDate() {
        UserEntity child = user(CHILD_ID, CHILD_BIRTH, "child@example.com", null);
        when(parentalConsentService.listApprovedParentChildPairs(0, 500))
                .thenReturn(List.of(new ParentalConsentService.ParentChildPair(GUARDIAN_ID, CHILD_ID)));
        when(careLinkService.listActiveParentWatcherPairs(0, 500)).thenReturn(List.of());
        when(userRepository.findByIdIn(any())).thenReturn(List.of(child));

        newService(SEAL_DATE).execute();

        verify(transitionNotificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("封印後（境界日を過ぎている）は送信しない")
    void noSendAfterSeal() {
        UserEntity child = user(CHILD_ID, CHILD_BIRTH, "child@example.com", null);
        when(parentalConsentService.listApprovedParentChildPairs(0, 500))
                .thenReturn(List.of(new ParentalConsentService.ParentChildPair(GUARDIAN_ID, CHILD_ID)));
        when(careLinkService.listActiveParentWatcherPairs(0, 500)).thenReturn(List.of());
        when(userRepository.findByIdIn(any())).thenReturn(List.of(child));

        newService(SEAL_DATE.plusDays(10)).execute();

        verify(transitionNotificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("送信済み（既存記録あり）はスキップする")
    void skipsAlreadySent() {
        UserEntity child = user(CHILD_ID, CHILD_BIRTH, "child@example.com", null);
        when(parentalConsentService.listApprovedParentChildPairs(0, 500))
                .thenReturn(List.of(new ParentalConsentService.ParentChildPair(GUARDIAN_ID, CHILD_ID)));
        when(careLinkService.listActiveParentWatcherPairs(0, 500)).thenReturn(List.of());
        when(userRepository.findByIdIn(any())).thenReturn(List.of(child));
        when(transitionNotificationRepository
                .existsByNotificationKindAndRecipientUserIdAndChildUserIdAndSealDate(
                        eq(GuardianshipTransitionNotificationKind.PROGRESSION_NOTICE),
                        eq(GUARDIAN_ID), eq(CHILD_ID), eq(SEAL_DATE))).thenReturn(true);

        newService(LocalDate.of(2026, 2, 1)).execute();

        verify(transitionNotificationRepository, never()).save(any());
        verify(notificationHelper, never()).notify(anyLong(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("並行実行で送信記録 INSERT が UNIQUE 競合したら二重送信しない")
    void noDoubleSendOnUniqueConflict() {
        UserEntity child = user(CHILD_ID, CHILD_BIRTH, "child@example.com", null);
        when(parentalConsentService.listApprovedParentChildPairs(0, 500))
                .thenReturn(List.of(new ParentalConsentService.ParentChildPair(GUARDIAN_ID, CHILD_ID)));
        when(careLinkService.listActiveParentWatcherPairs(0, 500)).thenReturn(List.of());
        when(userRepository.findByIdIn(any())).thenReturn(List.of(child));
        when(userRepository.findById(GUARDIAN_ID))
                .thenReturn(java.util.Optional.of(user(GUARDIAN_ID, "1980-01-01", "parent@example.com", "h")));
        when(transitionNotificationRepository
                .existsByNotificationKindAndRecipientUserIdAndChildUserIdAndSealDate(
                        any(), any(), any(), any())).thenReturn(false);
        when(transitionNotificationRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("dup"));

        newService(LocalDate.of(2026, 2, 1)).execute();

        // 記録挿入が弾かれたら通知もメールも送らない。
        verify(notificationHelper, never()).notify(anyLong(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        verify(emailOutboxService, never()).enqueue(any());
    }

    @Test
    @DisplayName("生年月日解決不能の子はスキップする（安全側）")
    void skipsUnresolvableBirthDate() {
        UserEntity child = user(CHILD_ID, null, "child@example.com", null);
        when(parentalConsentService.listApprovedParentChildPairs(0, 500))
                .thenReturn(List.of(new ParentalConsentService.ParentChildPair(GUARDIAN_ID, CHILD_ID)));
        when(careLinkService.listActiveParentWatcherPairs(0, 500)).thenReturn(List.of());
        when(userRepository.findByIdIn(any())).thenReturn(List.of(child));

        newService(LocalDate.of(2026, 2, 1)).execute();

        verify(transitionNotificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("対象ペアが無ければ何もしない")
    void noPairsNoOp() {
        when(parentalConsentService.listApprovedParentChildPairs(0, 500)).thenReturn(List.of());
        when(careLinkService.listActiveParentWatcherPairs(0, 500)).thenReturn(List.of());

        newService(LocalDate.of(2026, 2, 1)).execute();

        verify(userRepository, never()).findByIdIn(any());
        verify(notificationHelper, never()).notify(anyLong(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }
}
