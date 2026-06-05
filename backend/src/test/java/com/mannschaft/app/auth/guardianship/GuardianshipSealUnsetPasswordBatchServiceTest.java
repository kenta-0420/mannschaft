package com.mannschaft.app.auth.guardianship;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthPasswordResetService;
import com.mannschaft.app.auth.service.ParentalConsentService;
import com.mannschaft.app.family.service.CareLinkService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GuardianshipSealUnsetPasswordBatchService} の単体テスト（F08.9 P3c-3 封印時未設定メール）。
 *
 * <p>JP: 2013-04-02 生まれ → 封印境界日 2026-04-01。封印日到来かつパスワード未設定の子へ
 * パスワード設定メールを送る。プレースホルダメール・送信済み・パスワード設定済みはスキップ。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GuardianshipSealUnsetPasswordBatchService")
class GuardianshipSealUnsetPasswordBatchServiceTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final Long CHILD_ID = 200L;
    private static final String CHILD_BIRTH = "2013-04-02";
    private static final LocalDate SEAL_DATE = LocalDate.of(2026, 4, 1);

    @Mock private ParentalConsentService parentalConsentService;
    @Mock private CareLinkService careLinkService;
    @Mock private UserRepository userRepository;
    @Mock private GuardianshipTransitionNotificationRepository transitionNotificationRepository;
    @Mock private AuthPasswordResetService authPasswordResetService;

    private GuardianshipSealUnsetPasswordBatchService newService(LocalDate today) {
        Clock clock = Clock.fixed(today.atStartOfDay(JST).toInstant(), JST);
        GuardianshipAgePolicyRegistry registry = new GuardianshipAgePolicyRegistry(
                List.of(new JapanGuardianshipAgePolicy()), new DefaultGuardianshipAgePolicy());
        return new GuardianshipSealUnsetPasswordBatchService(
                parentalConsentService, careLinkService, userRepository, registry,
                transitionNotificationRepository, authPasswordResetService, clock);
    }

    private UserEntity child(String birthDate, String email, String passwordHash) {
        UserEntity u = UserEntity.builder()
                .email(email)
                .lastName("山田").firstName("太郎").displayName("たろう")
                .locale("ja").timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .countryCode("JP")
                .birthDate(birthDate)
                .passwordHash(passwordHash)
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(u, "id", CHILD_ID);
        return u;
    }

    private void stubSingleChild(UserEntity c) {
        when(parentalConsentService.listApprovedParentChildPairs(0, 500))
                .thenReturn(List.of(new ParentalConsentService.ParentChildPair(100L, CHILD_ID)));
        when(careLinkService.listActiveParentWatcherPairs(0, 500)).thenReturn(List.of());
        when(userRepository.findByIdIn(any())).thenReturn(List.of(c));
    }

    /** save が永続化済みエンティティを返すスタブ（補償削除の引数検証用に同一参照を返す）。 */
    private void stubSaveReturnsArgument() {
        when(transitionNotificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("封印日当日・パスワード未設定・実メールなら送付する（バッチ専用経路を使う）")
    void sendsOnSealDateWhenPasswordUnset() {
        stubSingleChild(child(CHILD_BIRTH, "child@example.com", null));
        when(transitionNotificationRepository
                .existsByNotificationKindAndRecipientUserIdAndChildUserIdAndSealDate(
                        any(), any(), any(), any())).thenReturn(false);
        stubSaveReturnsArgument();

        newService(SEAL_DATE).execute();

        // バッチ専用経路（IP レート制限を通さない）を使う。公開メソッドは呼ばない。
        verify(authPasswordResetService).requestPasswordResetForSystemBatch(eq("child@example.com"));
        verify(authPasswordResetService, never()).requestPasswordReset(anyString(), anyString());
        ArgumentCaptor<GuardianshipTransitionNotificationEntity> rec =
                ArgumentCaptor.forClass(GuardianshipTransitionNotificationEntity.class);
        verify(transitionNotificationRepository).save(rec.capture());
        assertThat(rec.getValue().getNotificationKind())
                .isEqualTo(GuardianshipTransitionNotificationKind.SEAL_UNSET_PASSWORD);
        assertThat(rec.getValue().getRecipientUserId()).isEqualTo(CHILD_ID);
        assertThat(rec.getValue().getSealDate()).isEqualTo(SEAL_DATE);
        // 送付成功時は補償削除しない。
        verify(transitionNotificationRepository, never()).delete(any());
    }

    @Test
    @DisplayName("封印日より前は送付しない")
    void noSendBeforeSeal() {
        stubSingleChild(child(CHILD_BIRTH, "child@example.com", null));

        newService(SEAL_DATE.minusDays(1)).execute();

        verify(authPasswordResetService, never()).requestPasswordResetForSystemBatch(anyString());
        verify(transitionNotificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("封印日を過ぎていても未設定なら送付する（取り残し防止）")
    void sendsAfterSeal() {
        stubSingleChild(child(CHILD_BIRTH, "child@example.com", null));
        when(transitionNotificationRepository
                .existsByNotificationKindAndRecipientUserIdAndChildUserIdAndSealDate(
                        any(), any(), any(), any())).thenReturn(false);
        stubSaveReturnsArgument();

        newService(SEAL_DATE.plusMonths(2)).execute();

        verify(authPasswordResetService).requestPasswordResetForSystemBatch(eq("child@example.com"));
    }

    @Test
    @DisplayName("パスワード設定済みは送付しない")
    void noSendWhenPasswordSet() {
        stubSingleChild(child(CHILD_BIRTH, "child@example.com", "argon2-hash"));

        newService(SEAL_DATE).execute();

        verify(authPasswordResetService, never()).requestPasswordResetForSystemBatch(anyString());
        verify(transitionNotificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("内部プレースホルダメールは送付不能としてスキップする")
    void skipsPlaceholderEmail() {
        stubSingleChild(child(CHILD_BIRTH, "deleted-200@anon.mannschaft.internal", null));

        newService(SEAL_DATE).execute();

        verify(authPasswordResetService, never()).requestPasswordResetForSystemBatch(anyString());
        verify(transitionNotificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("送信済み（既存記録あり）はスキップする")
    void skipsAlreadySent() {
        stubSingleChild(child(CHILD_BIRTH, "child@example.com", null));
        when(transitionNotificationRepository
                .existsByNotificationKindAndRecipientUserIdAndChildUserIdAndSealDate(
                        eq(GuardianshipTransitionNotificationKind.SEAL_UNSET_PASSWORD),
                        eq(CHILD_ID), eq(CHILD_ID), eq(SEAL_DATE))).thenReturn(true);

        newService(SEAL_DATE).execute();

        verify(transitionNotificationRepository, never()).save(any());
        verify(authPasswordResetService, never()).requestPasswordResetForSystemBatch(anyString());
    }

    @Test
    @DisplayName("並行実行で送信記録 INSERT が UNIQUE 競合（DuplicateKeyException）したら送付しない")
    void noDoubleSendOnUniqueConflict() {
        stubSingleChild(child(CHILD_BIRTH, "child@example.com", null));
        when(transitionNotificationRepository
                .existsByNotificationKindAndRecipientUserIdAndChildUserIdAndSealDate(
                        any(), any(), any(), any())).thenReturn(false);
        when(transitionNotificationRepository.save(any()))
                .thenThrow(new DuplicateKeyException("dup"));

        newService(SEAL_DATE).execute();

        verify(authPasswordResetService, never()).requestPasswordResetForSystemBatch(anyString());
        verify(transitionNotificationRepository, never()).delete(any());
    }

    @Test
    @DisplayName("送付が失敗したら先行保存した送信記録を補償削除し、翌日再送可能にする")
    void compensatesRecordWhenSendFails() {
        stubSingleChild(child(CHILD_BIRTH, "child@example.com", null));
        when(transitionNotificationRepository
                .existsByNotificationKindAndRecipientUserIdAndChildUserIdAndSealDate(
                        any(), any(), any(), any())).thenReturn(false);
        stubSaveReturnsArgument();
        // 送付（バッチ専用経路）が例外で失敗する。
        doThrow(new RuntimeException("outbox enqueue failed"))
                .when(authPasswordResetService).requestPasswordResetForSystemBatch(anyString());

        newService(SEAL_DATE).execute();

        // 先行保存した記録（save の戻り）を補償削除する → 翌日のバッチで再送できる。
        ArgumentCaptor<GuardianshipTransitionNotificationEntity> saved =
                ArgumentCaptor.forClass(GuardianshipTransitionNotificationEntity.class);
        verify(transitionNotificationRepository).save(saved.capture());
        verify(transitionNotificationRepository).delete(saved.getValue());
    }

    @Test
    @DisplayName("生年月日解決不能の子はスキップする")
    void skipsUnresolvableBirthDate() {
        stubSingleChild(child(null, "child@example.com", null));

        newService(SEAL_DATE).execute();

        verify(authPasswordResetService, never()).requestPasswordResetForSystemBatch(anyString());
        verify(transitionNotificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("対象子が無ければ何もしない")
    void noChildrenNoOp() {
        when(parentalConsentService.listApprovedParentChildPairs(0, 500)).thenReturn(List.of());
        when(careLinkService.listActiveParentWatcherPairs(0, 500)).thenReturn(List.of());

        newService(SEAL_DATE).execute();

        verify(userRepository, never()).findByIdIn(any());
    }
}
