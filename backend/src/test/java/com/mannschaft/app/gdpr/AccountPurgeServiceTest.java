package com.mannschaft.app.gdpr;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.EmailChangeTokenRepository;
import com.mannschaft.app.auth.repository.EmailVerificationTokenRepository;
import com.mannschaft.app.auth.repository.MfaRecoveryTokenRepository;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.auth.repository.OAuthLinkTokenRepository;
import com.mannschaft.app.auth.repository.PasswordResetTokenRepository;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.repository.WebAuthnCredentialRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.gdpr.entity.AccountPurgeCompletionStatusEntity;
import com.mannschaft.app.gdpr.entity.DataExportEntity;
import com.mannschaft.app.gdpr.entity.GdprS3PurgeFailureEntity;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import com.mannschaft.app.gdpr.repository.DataExportRepository;
import com.mannschaft.app.gdpr.repository.GdprS3PurgeFailureRepository;
import com.mannschaft.app.gdpr.service.AccountPurgeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * AccountPurgeService 単体テスト。
 *
 * <p>Phase C（越境 DML 撤去）完了後のテスト。
 * クロスドメイン Repository（chart / errorreport / role / team / payment / proxy）は
 * AccountPurgeService には注入されなくなった。
 * 各ドメインの *PurgeEventListener が AccountPurgedEvent を購読して処理する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountPurgeService 単体テスト")
class AccountPurgeServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private DataExportRepository dataExportRepository;
    @Mock
    private StorageService storageService;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private EmailVerificationTokenRepository emailVerificationTokenRepository;
    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock
    private EmailChangeTokenRepository emailChangeTokenRepository;
    @Mock
    private MfaRecoveryTokenRepository mfaRecoveryTokenRepository;
    @Mock
    private OAuthLinkTokenRepository oAuthLinkTokenRepository;
    @Mock
    private OAuthAccountRepository oAuthAccountRepository;
    @Mock
    private TwoFactorAuthRepository twoFactorAuthRepository;
    @Mock
    private WebAuthnCredentialRepository webAuthnCredentialRepository;
    @Mock
    private ParentalConsentLinkRepository parentalConsentLinkRepository;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AccountPurgeCompletionStatusRepository completionStatusRepository;
    @Mock
    private GdprS3PurgeFailureRepository gdprS3PurgeFailureRepository;

    @InjectMocks
    private AccountPurgeService service;

    private static final Long USER_ID = 100L;

    private UserEntity buildUser(Long id) {
        UserEntity user = UserEntity.builder()
                .email("user" + id + "@example.com")
                .lastName("田中")
                .firstName("太郎")
                .displayName("taro")
                .isSearchable(true)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .build();
        // idをリフレクションで設定
        try {
            var baseEntityClass = com.mannschaft.app.common.BaseEntity.class;
            var idField = baseEntityClass.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (Exception e) {
            throw new RuntimeException("テスト用エンティティ構築失敗", e);
        }
        return user;
    }

    /** テスト共通の mock スタブ設定（auth ドメイン・gdpr ドメイン） */
    private void stubAuthAndGdprMocks(Long userId) {
        given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId)).willReturn(List.of());
        given(oAuthAccountRepository.findByUserId(userId)).willReturn(List.of());
        given(twoFactorAuthRepository.findByUserId(userId)).willReturn(Optional.empty());
        given(webAuthnCredentialRepository.findByUserId(userId)).willReturn(List.of());
        given(dataExportRepository.findByExpiresAtBeforeAndS3KeyIsNotNull(any())).willReturn(List.of());
    }

    @Nested
    @DisplayName("purgeExpiredAccounts")
    class PurgeExpiredAccounts {

        @Test
        @DisplayName("正常系: 対象ユーザーなしの場合、削除が実行されない")
        void 正常_対象なし_削除されない() {
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of());

            assertThatCode(() -> service.purgeExpiredAccounts())
                    .doesNotThrowAnyException();

            verify(userRepository, never()).delete(any(UserEntity.class));
        }

        @Test
        @DisplayName("正常系: ユーザーが物理削除される")
        void 正常_ユーザー物理削除() {
            UserEntity user = buildUser(USER_ID);
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user));
            stubAuthAndGdprMocks(USER_ID);

            service.purgeExpiredAccounts();

            verify(userRepository).delete(user);
        }

        @Test
        @DisplayName("正常系: WITHDRAWAL_COMPLETED 監査ログが記録される")
        void 正常_WITHDRAWAL_COMPLETED監査ログ記録() {
            UserEntity user = buildUser(USER_ID);
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user));
            stubAuthAndGdprMocks(USER_ID);

            service.purgeExpiredAccounts();

            // WITHDRAWAL_COMPLETED 監査ログが targetUserId=USER_ID で記録されること
            verify(auditLogService).record(
                    eq("WITHDRAWAL_COMPLETED"),
                    isNull(),
                    eq(USER_ID),
                    isNull(), isNull(), isNull(), isNull(), isNull(),
                    any()
            );
        }

        @Test
        @DisplayName("正常系: AccountPurgedEvent が userId と emailHash で発火される（Phase B-1）")
        void 正常_AccountPurgedEvent発火() {
            UserEntity user = buildUser(USER_ID);
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user));
            stubAuthAndGdprMocks(USER_ID);

            service.purgeExpiredAccounts();

            // AccountPurgedEvent が userId / emailHash 整合性で発火されることを検証
            ArgumentCaptor<AccountPurgedEvent> captor = ArgumentCaptor.forClass(AccountPurgedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            AccountPurgedEvent event = captor.getValue();
            assertThat(event.getUserId()).isEqualTo(USER_ID);
            assertThat(event.getEmailHash()).isNotBlank();
            // SHA-256 hex 文字列なので 64 文字
            assertThat(event.getEmailHash()).hasSize(64);
        }

        @Test
        @DisplayName("Phase D-8/残債1: AccountPurgedEvent 発火前に 8 ドメイン分の PENDING レコードが INSERT される")
        void PhaseD8_PENDING_レコードがINSERTされる() {
            UserEntity user = buildUser(USER_ID);
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user));
            stubAuthAndGdprMocks(USER_ID);

            service.purgeExpiredAccounts();

            // 8 ドメイン（role/team/payment/chart/proxy/errorreport/resume/billing）分の save が呼ばれること
            verify(completionStatusRepository, atLeast(8)).save(any(AccountPurgeCompletionStatusEntity.class));

            // 各ドメイン名の PENDING レコードが INSERT されること（残債1: billing を追加登録）
            for (String domain : List.of(
                    "role", "team", "payment", "chart", "proxy", "errorreport", "resume", "billing")) {
                verify(completionStatusRepository).save(argThat(entity ->
                        entity.getUserId().equals(USER_ID)
                                && entity.getDomainName().equals(domain)
                                && "PENDING".equals(entity.getStatus())
                                && entity.getAttemptedAt() != null
                                && entity.getCompletedAt() == null
                ));
            }
        }

        @Test
        @DisplayName("異常系: 1件失敗でも他のユーザーの削除が継続する")
        void 異常_1件失敗_他継続() {
            UserEntity user1 = buildUser(USER_ID);
            UserEntity user2 = buildUser(USER_ID + 1);

            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user1, user2));

            // user1の処理で例外をスロー
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(USER_ID))
                    .willThrow(new RuntimeException("DB error"));

            // user2の処理は正常
            stubAuthAndGdprMocks(USER_ID + 1);

            // 例外がスローされずに全体が完了する
            assertThatCode(() -> service.purgeExpiredAccounts())
                    .doesNotThrowAnyException();

            // user2は処理される
            verify(userRepository).delete(user2);
        }

        @Test
        @DisplayName("Phase C: data_exports S3削除が実行される（gdpr 自ドメイン操作の継続確認）")
        void PhaseC_dataExports_S3削除実行() {
            UserEntity user = buildUser(USER_ID);
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user));

            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(USER_ID)).willReturn(List.of());
            given(oAuthAccountRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(twoFactorAuthRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(webAuthnCredentialRepository.findByUserId(USER_ID)).willReturn(List.of());

            // DataExport が 1 件ある場合
            DataExportEntity dataExport = DataExportEntity.builder()
                    .userId(USER_ID)
                    .status("COMPLETED")
                    .s3Key("exports/100/data.zip")
                    .build();
            given(dataExportRepository.findByExpiresAtBeforeAndS3KeyIsNotNull(any()))
                    .willReturn(List.of(dataExport));

            service.purgeExpiredAccounts();

            verify(storageService).delete("exports/100/data.zip");
            verify(dataExportRepository).delete(dataExport);
        }

        @Test
        @DisplayName("異常系: S3削除失敗時に GdprS3PurgeFailureRepository.save が呼ばれる（失敗テーブルへ記録）")
        void 異常_S3削除失敗_失敗テーブルへ記録() {
            UserEntity user = buildUser(USER_ID);
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user));

            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(USER_ID)).willReturn(List.of());
            given(oAuthAccountRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(twoFactorAuthRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(webAuthnCredentialRepository.findByUserId(USER_ID)).willReturn(List.of());

            // DataExport が 1 件あり、S3削除が失敗する場合
            DataExportEntity dataExport = DataExportEntity.builder()
                    .userId(USER_ID)
                    .status("COMPLETED")
                    .s3Key("exports/100/data.zip")
                    .build();
            given(dataExportRepository.findByExpiresAtBeforeAndS3KeyIsNotNull(any()))
                    .willReturn(List.of(dataExport));
            org.mockito.Mockito.doThrow(new RuntimeException("S3 connection refused"))
                    .when(storageService).delete("exports/100/data.zip");

            // S3削除失敗でも例外はスローされず処理継続する
            assertThatCode(() -> service.purgeExpiredAccounts())
                    .doesNotThrowAnyException();

            // GdprS3PurgeFailureRepository.save が呼ばれ、失敗情報が記録されること
            verify(gdprS3PurgeFailureRepository).save(argThat(failure ->
                    failure.getUserId().equals(USER_ID)
                            && failure.getS3Key().equals("exports/100/data.zip")
                            && failure.getFailedAt() != null
                            && failure.getLastError() != null
                            && failure.getLastError().contains("S3 connection refused")
            ));

            // dataExport レコードは（S3失敗後でも）削除されること
            verify(dataExportRepository).delete(dataExport);
        }

        @Test
        @DisplayName("GDPR §17: purgeUser実行時にpassword_reset_tokensが削除される")
        void GDPR_password_reset_tokens削除() {
            UserEntity user = buildUser(USER_ID);
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user));
            stubAuthAndGdprMocks(USER_ID);

            service.purgeExpiredAccounts();

            verify(passwordResetTokenRepository).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("GDPR §17: purgeUser実行時にemail_change_tokensが削除される")
        void GDPR_email_change_tokens削除() {
            UserEntity user = buildUser(USER_ID);
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user));
            stubAuthAndGdprMocks(USER_ID);

            service.purgeExpiredAccounts();

            verify(emailChangeTokenRepository).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("GDPR §17: purgeUser実行時にmfa_recovery_tokensが削除される")
        void GDPR_mfa_recovery_tokens削除() {
            UserEntity user = buildUser(USER_ID);
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user));
            stubAuthAndGdprMocks(USER_ID);

            service.purgeExpiredAccounts();

            verify(mfaRecoveryTokenRepository).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("GDPR §17: purgeUser実行時にoauth_link_tokensが削除される")
        void GDPR_oauth_link_tokens削除() {
            UserEntity user = buildUser(USER_ID);
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user));
            stubAuthAndGdprMocks(USER_ID);

            service.purgeExpiredAccounts();

            verify(oAuthLinkTokenRepository).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("GDPR §17: purgeUser実行時に4種のトークンが全件削除される（一括確認）")
        void GDPR_4種トークン全件削除() {
            UserEntity user = buildUser(USER_ID);
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user));
            stubAuthAndGdprMocks(USER_ID);

            service.purgeExpiredAccounts();

            // 4種類のトークン削除が全て実行されることを確認
            verify(passwordResetTokenRepository).deleteByUserId(USER_ID);
            verify(emailChangeTokenRepository).deleteByUserId(USER_ID);
            verify(mfaRecoveryTokenRepository).deleteByUserId(USER_ID);
            verify(oAuthLinkTokenRepository).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("異常系: S3削除失敗エラーメッセージが500文字を超える場合、切り詰めて記録する")
        void 異常_S3削除失敗_エラーメッセージ切り詰め() {
            UserEntity user = buildUser(USER_ID);
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user));

            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(USER_ID)).willReturn(List.of());
            given(oAuthAccountRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(twoFactorAuthRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(webAuthnCredentialRepository.findByUserId(USER_ID)).willReturn(List.of());

            DataExportEntity dataExport = DataExportEntity.builder()
                    .userId(USER_ID)
                    .status("COMPLETED")
                    .s3Key("exports/100/data.zip")
                    .build();
            given(dataExportRepository.findByExpiresAtBeforeAndS3KeyIsNotNull(any()))
                    .willReturn(List.of(dataExport));

            // 501文字のエラーメッセージ
            String longMessage = "E".repeat(501);
            org.mockito.Mockito.doThrow(new RuntimeException(longMessage))
                    .when(storageService).delete("exports/100/data.zip");

            assertThatCode(() -> service.purgeExpiredAccounts())
                    .doesNotThrowAnyException();

            // lastError が 500 文字に切り詰められていること
            verify(gdprS3PurgeFailureRepository).save(argThat(failure ->
                    failure.getLastError() != null
                            && failure.getLastError().length() == 500
            ));
        }
    }
}
