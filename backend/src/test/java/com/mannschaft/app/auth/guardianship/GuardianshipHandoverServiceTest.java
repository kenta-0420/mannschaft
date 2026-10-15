package com.mannschaft.app.auth.guardianship;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.auth.service.AuthPasswordResetService;
import com.mannschaft.app.auth.service.ParentalConsentService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.service.CareLinkService;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link GuardianshipHandoverService#initiateHandover} の単体テスト（F08.9 P3c-2）。
 *
 * <h3>テスト観点（02_api_design §2.3 / 03_security §3.2）</h3>
 * <ul>
 *   <li>子メール有り正常系: 既存メールへパスワード設定リンク送付（enqueue は AuthPasswordResetService 経由）</li>
 *   <li>childEmail 指定で登録→送付（子がメール未登録の場合）</li>
 *   <li>既存メール有り × childEmail 指定 → 400（上書き拒否）</li>
 *   <li>メール無し × childEmail 未指定 → 400</li>
 *   <li>childEmail 重複 → 400（AUTH_013）</li>
 *   <li>IDOR: 有効な保護者リンクなし → 403</li>
 *   <li>acting-as 中 → 403（assertNotActingAs が先に弾く）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GuardianshipHandoverService テスト（F08.9 P3c-2）")
class GuardianshipHandoverServiceTest {

    private static final Long GUARDIAN_ID = 100L;
    private static final Long CHILD_ID = 11L;
    private static final String IP = "203.0.113.7";

    @Mock private ParentalConsentService parentalConsentService;
    @Mock private CareLinkService careLinkService;
    @Mock private UserRepository userRepository;
    @Mock private AuthPasswordResetService authPasswordResetService;
    @Mock private AuthenticationCriticalOperationGuard authenticationCriticalOperationGuard;
    @Mock private AuditLogService auditLogService;

    private GuardianshipHandoverService service;

    @BeforeEach
    void setUp() {
        service = new GuardianshipHandoverService(
                parentalConsentService, careLinkService, userRepository,
                authPasswordResetService, authenticationCriticalOperationGuard, auditLogService);
    }

    /** email を指定して子ユーザーを生成する。null/内部プレースホルダで「メール未登録」を表す。 */
    private UserEntity child(String email) {
        UserEntity user = UserEntity.builder()
                .email(email)
                .lastName("山田")
                .firstName("子")
                .displayName("子")
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(user, "id", CHILD_ID);
        return user;
    }

    private void givenApprovedGuardian() {
        given(parentalConsentService.isApprovedGuardian(GUARDIAN_ID, CHILD_ID)).willReturn(true);
    }

    @Nested
    @DisplayName("正常系")
    class Ok {

        @Test
        @DisplayName("子メール有り: 既存メールへパスワード設定リンク送付＋監査記録（メール登録なし）")
        void existingEmail_sendsResetLink() {
            givenApprovedGuardian();
            given(userRepository.findById(CHILD_ID))
                    .willReturn(Optional.of(child("child@example.com")));

            service.initiateHandover(GUARDIAN_ID, CHILD_ID, null, IP);

            // 既存メールへリセット送付（流用）。
            verify(authPasswordResetService).requestPasswordReset(eq("child@example.com"), eq(IP));
            // メール登録はしない。
            verify(userRepository, never()).save(any());
            // 監査記録（registeredNewEmail=false）。
            ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
            verify(auditLogService).record(
                    eq(AuditEventType.GUARDIANSHIP_HANDOVER_INITIATED.name()),
                    eq(GUARDIAN_ID), eq(CHILD_ID),
                    any(), any(), any(), any(), any(), meta.capture());
            assertThat(meta.getValue()).contains("\"registeredNewEmail\":false");
        }

        @Test
        @DisplayName("子メール未登録（内部プレースホルダ）＋ childEmail 指定: メール登録→送付")
        void noEmail_registersAndSends() {
            givenApprovedGuardian();
            given(userRepository.findById(CHILD_ID))
                    .willReturn(Optional.of(child("managed-abc@x.mannschaft.internal")));
            given(userRepository.existsByEmail("new-child@example.com")).willReturn(false);

            service.initiateHandover(GUARDIAN_ID, CHILD_ID, "new-child@example.com", IP);

            // users.email を登録（toBuilder で差し替え保存）。
            ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
            verify(userRepository).save(saved.capture());
            assertThat(saved.getValue().getEmail()).isEqualTo("new-child@example.com");
            // 登録したメールへ送付。
            verify(authPasswordResetService).requestPasswordReset(eq("new-child@example.com"), eq(IP));
            ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
            verify(auditLogService).record(
                    eq(AuditEventType.GUARDIANSHIP_HANDOVER_INITIATED.name()),
                    eq(GUARDIAN_ID), eq(CHILD_ID),
                    any(), any(), any(), any(), any(), meta.capture());
            assertThat(meta.getValue()).contains("\"registeredNewEmail\":true");
        }
    }

    @Nested
    @DisplayName("異常系")
    class Errors {

        @Test
        @DisplayName("既存メール有り × childEmail 指定 → 400（上書き拒否・MEMBERSHIP_BILLING_006）")
        void existingEmail_withChildEmail_400() {
            givenApprovedGuardian();
            given(userRepository.findById(CHILD_ID))
                    .willReturn(Optional.of(child("child@example.com")));

            assertThatThrownBy(() ->
                    service.initiateHandover(GUARDIAN_ID, CHILD_ID, "other@example.com", IP))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(MembershipBillingErrorCode.GUARDIANSHIP_HANDOVER_EMAIL_REQUIRED);

            verify(authPasswordResetService, never()).requestPasswordReset(anyString(), anyString());
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("メール無し × childEmail 未指定 → 400（MEMBERSHIP_BILLING_006）")
        void noEmail_noChildEmail_400() {
            givenApprovedGuardian();
            given(userRepository.findById(CHILD_ID))
                    .willReturn(Optional.of(child("placeholder@x.mannschaft.internal")));

            assertThatThrownBy(() ->
                    service.initiateHandover(GUARDIAN_ID, CHILD_ID, null, IP))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(MembershipBillingErrorCode.GUARDIANSHIP_HANDOVER_EMAIL_REQUIRED);

            verify(authPasswordResetService, never()).requestPasswordReset(anyString(), anyString());
        }

        @Test
        @DisplayName("childEmail が他ユーザーで使用済み → 400（AUTH_013）")
        void duplicateEmail_400() {
            givenApprovedGuardian();
            given(userRepository.findById(CHILD_ID))
                    .willReturn(Optional.of(child("placeholder@x.mannschaft.internal")));
            given(userRepository.existsByEmail("taken@example.com")).willReturn(true);

            assertThatThrownBy(() ->
                    service.initiateHandover(GUARDIAN_ID, CHILD_ID, "taken@example.com", IP))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.AUTH_013);

            verify(userRepository, never()).save(any());
            verify(authPasswordResetService, never()).requestPasswordReset(anyString(), anyString());
        }

        @Test
        @DisplayName("IDOR: 有効な保護者リンクなし → 403 GUARDIANSHIP_LINK_NOT_FOUND")
        void idor_403() {
            given(parentalConsentService.isApprovedGuardian(GUARDIAN_ID, CHILD_ID)).willReturn(false);
            given(careLinkService.isActiveParentWatcher(GUARDIAN_ID, CHILD_ID)).willReturn(false);

            assertThatThrownBy(() ->
                    service.initiateHandover(GUARDIAN_ID, CHILD_ID, null, IP))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(MembershipBillingErrorCode.GUARDIANSHIP_LINK_NOT_FOUND);

            verify(authPasswordResetService, never()).requestPasswordReset(anyString(), anyString());
        }

        @Test
        @DisplayName("acting-as 中 → 403（assertNotActingAs が先に弾く・リンク照会前）")
        void actingAs_403() {
            doThrow(new BusinessException(
                    MembershipBillingErrorCode.MEMBERSHIP_AUTHENTICATION_CRITICAL_OPERATION))
                    .when(authenticationCriticalOperationGuard).assertNotActingAs();

            assertThatThrownBy(() ->
                    service.initiateHandover(GUARDIAN_ID, CHILD_ID, null, IP))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(MembershipBillingErrorCode.MEMBERSHIP_AUTHENTICATION_CRITICAL_OPERATION);

            // ガードで弾かれるため一切先へ進まない。
            verify(parentalConsentService, never()).isApprovedGuardian(any(), any());
            verify(authPasswordResetService, never()).requestPasswordReset(anyString(), anyString());
        }
    }
}
