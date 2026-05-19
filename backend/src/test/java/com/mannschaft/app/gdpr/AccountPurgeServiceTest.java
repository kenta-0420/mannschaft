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
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.errorreport.repository.ErrorReportOccurrenceRepository;
import com.mannschaft.app.gdpr.entity.DataExportEntity;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.repository.DataExportRepository;
import com.mannschaft.app.gdpr.service.AccountPurgeService;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountPurgeService 単体テスト")
class AccountPurgeServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private ChartRecordRepository chartRecordRepository;
    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private TeamOrgMembershipRepository teamOrgMembershipRepository;
    @Mock
    private MemberPaymentRepository memberPaymentRepository;
    @Mock
    private StripeCustomerRepository stripeCustomerRepository;
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
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @Mock
    private ProxyInputRecordRepository proxyInputRecordRepository;
    @Mock
    private ErrorReportOccurrenceRepository errorReportOccurrenceRepository;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

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
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(USER_ID)).willReturn(List.of());
            given(oAuthAccountRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(twoFactorAuthRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(webAuthnCredentialRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(chartRecordRepository.anonymizeCustomerUserId(USER_ID)).willReturn(0);
            given(memberPaymentRepository.anonymizeUserId(any(), any())).willReturn(0);
            given(stripeCustomerRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(dataExportRepository.findByExpiresAtBeforeAndS3KeyIsNotNull(any())).willReturn(List.of());

            service.purgeExpiredAccounts();

            verify(userRepository).delete(user);
        }

        @Test
        @DisplayName("正常系: WITHDRAWAL_COMPLETED 監査ログが記録される")
        void 正常_WITHDRAWAL_COMPLETED監査ログ記録() {
            UserEntity user = buildUser(USER_ID);
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user));
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(USER_ID)).willReturn(List.of());
            given(oAuthAccountRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(twoFactorAuthRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(webAuthnCredentialRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(chartRecordRepository.anonymizeCustomerUserId(USER_ID)).willReturn(0);
            given(memberPaymentRepository.anonymizeUserId(any(), any())).willReturn(0);
            given(stripeCustomerRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(dataExportRepository.findByExpiresAtBeforeAndS3KeyIsNotNull(any())).willReturn(List.of());

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
        @DisplayName("正常系: chart_records匿名化が呼ばれる")
        void 正常_chartRecords匿名化() {
            UserEntity user = buildUser(USER_ID);
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user));
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(USER_ID)).willReturn(List.of());
            given(oAuthAccountRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(twoFactorAuthRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(webAuthnCredentialRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(chartRecordRepository.anonymizeCustomerUserId(USER_ID)).willReturn(1);
            given(memberPaymentRepository.anonymizeUserId(any(), any())).willReturn(0);
            given(stripeCustomerRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(dataExportRepository.findByExpiresAtBeforeAndS3KeyIsNotNull(any())).willReturn(List.of());

            service.purgeExpiredAccounts();

            verify(chartRecordRepository).anonymizeCustomerUserId(USER_ID);
        }

        @Test
        @DisplayName("正常系: member_paymentsセンチネル差替が呼ばれる")
        void 正常_memberPaymentsセンチネル差替() {
            UserEntity user = buildUser(USER_ID);
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user));
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(USER_ID)).willReturn(List.of());
            given(oAuthAccountRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(twoFactorAuthRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(webAuthnCredentialRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(chartRecordRepository.anonymizeCustomerUserId(USER_ID)).willReturn(0);
            given(memberPaymentRepository.anonymizeUserId(any(), any())).willReturn(1);
            given(stripeCustomerRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(dataExportRepository.findByExpiresAtBeforeAndS3KeyIsNotNull(any())).willReturn(List.of());

            service.purgeExpiredAccounts();

            verify(memberPaymentRepository).anonymizeUserId(any(), any());
        }

        @Test
        @DisplayName("正常系: error_report_occurrencesの匿名化が呼ばれる（P2-F）")
        void 正常_errorReportOccurrences匿名化() {
            UserEntity user = buildUser(USER_ID);
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user));
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(USER_ID)).willReturn(List.of());
            given(oAuthAccountRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(twoFactorAuthRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(webAuthnCredentialRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(chartRecordRepository.anonymizeCustomerUserId(USER_ID)).willReturn(0);
            given(errorReportOccurrenceRepository.anonymizeByUserId(USER_ID)).willReturn(3);
            given(memberPaymentRepository.anonymizeUserId(any(), any())).willReturn(0);
            given(stripeCustomerRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(dataExportRepository.findByExpiresAtBeforeAndS3KeyIsNotNull(any())).willReturn(List.of());

            service.purgeExpiredAccounts();

            // 物理削除前に anonymizeByUserId が呼ばれていることを検証する
            verify(errorReportOccurrenceRepository).anonymizeByUserId(USER_ID);
            verify(userRepository).delete(user);
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
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(USER_ID + 1)).willReturn(List.of());
            given(oAuthAccountRepository.findByUserId(USER_ID + 1)).willReturn(List.of());
            given(twoFactorAuthRepository.findByUserId(USER_ID + 1)).willReturn(Optional.empty());
            given(webAuthnCredentialRepository.findByUserId(USER_ID + 1)).willReturn(List.of());
            given(chartRecordRepository.anonymizeCustomerUserId(USER_ID + 1)).willReturn(0);
            given(memberPaymentRepository.anonymizeUserId(any(), any())).willReturn(0);
            given(stripeCustomerRepository.findByUserId(USER_ID + 1)).willReturn(Optional.empty());
            given(dataExportRepository.findByExpiresAtBeforeAndS3KeyIsNotNull(any())).willReturn(List.of());

            // 例外がスローされずに全体が完了する
            assertThatCode(() -> service.purgeExpiredAccounts())
                    .doesNotThrowAnyException();

            // user2は処理される
            verify(userRepository).delete(user2);
        }

        @Test
        @DisplayName("Phase B-1: 物理削除完了時に AccountPurgedEvent が発火される")
        void Phase_B1_AccountPurgedEvent発火() {
            UserEntity user = buildUser(USER_ID);
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user));
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(USER_ID)).willReturn(List.of());
            given(oAuthAccountRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(twoFactorAuthRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(webAuthnCredentialRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(chartRecordRepository.anonymizeCustomerUserId(USER_ID)).willReturn(0);
            given(memberPaymentRepository.anonymizeUserId(any(), any())).willReturn(0);
            given(stripeCustomerRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(dataExportRepository.findByExpiresAtBeforeAndS3KeyIsNotNull(any())).willReturn(List.of());

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
    }
}
