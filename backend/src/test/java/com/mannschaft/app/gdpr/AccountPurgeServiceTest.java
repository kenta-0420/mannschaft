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
import com.mannschaft.app.auth.repository.WebAuthnCredentialRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.gdpr.entity.AccountPurgeCompletionStatusEntity;
import com.mannschaft.app.gdpr.entity.DataExportEntity;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import com.mannschaft.app.gdpr.repository.DataExportRepository;
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
    private AuditLogService auditLogService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AccountPurgeCompletionStatusRepository completionStatusRepository;

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
        @DisplayName("Phase D-8: AccountPurgedEvent 発火前に 6 ドメイン分の PENDING レコードが INSERT される")
        void PhaseD8_PENDING_レコードがINSERTされる() {
            UserEntity user = buildUser(USER_ID);
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user));
            stubAuthAndGdprMocks(USER_ID);

            service.purgeExpiredAccounts();

            // 6 ドメイン（role/team/payment/chart/proxy/errorreport）分の save が呼ばれること
            verify(completionStatusRepository, atLeast(6)).save(any(AccountPurgeCompletionStatusEntity.class));

            // 各ドメイン名の PENDING レコードが INSERT されること
            for (String domain : List.of("role", "team", "payment", "chart", "proxy", "errorreport")) {
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
    }
}
