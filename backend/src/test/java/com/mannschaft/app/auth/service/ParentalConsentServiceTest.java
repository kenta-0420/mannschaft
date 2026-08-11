package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link ParentalConsentService} の単体テスト。
 * F01.9 年齢確認・保護者同意機能のサービス層を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ParentalConsentService")
class ParentalConsentServiceTest {

    @InjectMocks
    private ParentalConsentService parentalConsentService;

    @Mock
    private ParentalConsentLinkRepository parentalConsentLinkRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuthTokenService authTokenService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private EmailOutboxService emailOutboxService;

    // ========================================
    // テストヘルパー
    // ========================================

    private UserEntity buildChildUser(Long id, String email) {
        return UserEntity.builder()
                .email(email)
                .lastName("山田")
                .firstName("太郎")
                .displayName("たろう")
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.PENDING_PARENTAL_CONSENT)
                .isSearchable(true)
                .birthDate("2010-01-01") // 未成年
                .build();
    }

    private UserEntity buildAdultParentUser(Long id, String email) {
        return UserEntity.builder()
                .email(email)
                .lastName("山田")
                .firstName("花子")
                .displayName("はなこ")
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .isSearchable(true)
                .birthDate("1980-01-01") // 成人
                .build();
    }

    private ParentalConsentLinkEntity buildPendingLink(Long childUserId, Long parentUserId, String parentEmail) {
        return ParentalConsentLinkEntity.builder()
                .childUserId(childUserId)
                .parentUserId(parentUserId)
                .parentEmail(parentEmail)
                .tokenHash("tokenHash123")
                .status(ParentalConsentLinkStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
    }

    private ParentalConsentLinkEntity buildApprovedLink(Long childUserId, Long parentUserId, String parentEmail) {
        return ParentalConsentLinkEntity.builder()
                .childUserId(childUserId)
                .parentUserId(parentUserId)
                .parentEmail(parentEmail)
                .tokenHash("tokenHash456")
                .status(ParentalConsentLinkStatus.APPROVED)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
    }

    // ========================================
    // inviteParent テスト
    // ========================================

    @Nested
    @DisplayName("inviteParent")
    class InviteParentTests {

        @Test
        @DisplayName("正常系: 正常な招待が保存されEmailOutboxServiceが呼ばれること")
        void inviteParent_success() {
            // given
            Long childUserId = 1L;
            String parentEmail = "parent@example.com";
            String childEmail = "child@example.com";

            UserEntity childUser = buildChildUser(childUserId, childEmail);
            given(userRepository.findById(childUserId)).willReturn(Optional.of(childUser));
            given(parentalConsentLinkRepository.countByChildUserIdAndStatus(
                    childUserId, ParentalConsentLinkStatus.PENDING)).willReturn(0L);
            given(parentalConsentLinkRepository.existsByChildUserIdAndParentEmailAndStatus(
                    childUserId, parentEmail, ParentalConsentLinkStatus.PENDING)).willReturn(false);
            given(userRepository.findByEmail(parentEmail)).willReturn(Optional.empty());
            given(authTokenService.hashToken(anyString())).willReturn("hashedToken");
            given(emailOutboxService.enqueue(any())).willReturn(UUID.randomUUID());

            // when
            parentalConsentService.inviteParent(childUserId, parentEmail);

            // then
            verify(parentalConsentLinkRepository).save(argThat(link ->
                    link.getChildUserId().equals(childUserId)
                            && link.getParentEmail().equals(parentEmail)
                            && link.getStatus() == ParentalConsentLinkStatus.PENDING
            ));
            ArgumentCaptor<EmailOutboxRequest> captor = ArgumentCaptor.forClass(EmailOutboxRequest.class);
            verify(emailOutboxService).enqueue(captor.capture());
            assertThat(captor.getValue().templateKind()).isEqualTo("PARENTAL_CONSENT_INVITATION");
            assertThat(captor.getValue().toAddress()).isEqualTo(parentEmail);
        }

        @Test
        @DisplayName("異常系: 自己招待でAUTH_069をスローすること")
        void inviteParent_selfInvitation_throwsAuth069() {
            // given
            Long childUserId = 1L;
            String childEmail = "child@example.com";
            UserEntity childUser = buildChildUser(childUserId, childEmail);
            given(userRepository.findById(childUserId)).willReturn(Optional.of(childUser));

            // when / then
            assertThatThrownBy(() -> parentalConsentService.inviteParent(childUserId, childEmail))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.AUTH_069);
        }

        @Test
        @DisplayName("異常系: PENDING上限3件超過でAUTH_067をスローすること")
        void inviteParent_pendingLimitExceeded_throwsAuth067() {
            // given
            Long childUserId = 1L;
            String parentEmail = "parent@example.com";
            UserEntity childUser = buildChildUser(childUserId, "child@example.com");
            given(userRepository.findById(childUserId)).willReturn(Optional.of(childUser));
            given(parentalConsentLinkRepository.countByChildUserIdAndStatus(
                    childUserId, ParentalConsentLinkStatus.PENDING)).willReturn(3L);

            // when / then
            assertThatThrownBy(() -> parentalConsentService.inviteParent(childUserId, parentEmail))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.AUTH_067);
        }

        @Test
        @DisplayName("異常系: 同一emailへの重複招待でAUTH_068をスローすること")
        void inviteParent_duplicateInvitation_throwsAuth068() {
            // given
            Long childUserId = 1L;
            String parentEmail = "parent@example.com";
            UserEntity childUser = buildChildUser(childUserId, "child@example.com");
            given(userRepository.findById(childUserId)).willReturn(Optional.of(childUser));
            given(parentalConsentLinkRepository.countByChildUserIdAndStatus(
                    childUserId, ParentalConsentLinkStatus.PENDING)).willReturn(0L);
            given(parentalConsentLinkRepository.existsByChildUserIdAndParentEmailAndStatus(
                    childUserId, parentEmail, ParentalConsentLinkStatus.PENDING)).willReturn(true);

            // when / then
            assertThatThrownBy(() -> parentalConsentService.inviteParent(childUserId, parentEmail))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.AUTH_068);
        }
    }

    // ========================================
    // approveParentalConsent テスト
    // ========================================

    @Nested
    @DisplayName("approveParentalConsent")
    class ApproveParentalConsentTests {

        @Test
        @DisplayName("正常系: 承認後に子ユーザーがACTIVEになること")
        void approveParentalConsent_success() {
            // given
            Long childUserId = 1L;
            Long parentUserId = 2L;
            String rawToken = "rawToken";

            ParentalConsentLinkEntity link = buildPendingLink(childUserId, null, "parent@example.com");
            given(authTokenService.hashToken(rawToken)).willReturn("tokenHash123");
            given(parentalConsentLinkRepository.findByTokenHash("tokenHash123"))
                    .willReturn(Optional.of(link));

            UserEntity parentUser = buildAdultParentUser(parentUserId, "parent@example.com");
            given(userRepository.findById(parentUserId)).willReturn(Optional.of(parentUser));

            UserEntity childUser = buildChildUser(childUserId, "child@example.com");
            given(userRepository.findById(childUserId)).willReturn(Optional.of(childUser));
            given(emailOutboxService.enqueue(any())).willReturn(UUID.randomUUID());

            // when
            parentalConsentService.approveParentalConsent(rawToken, parentUserId, "127.0.0.1");

            // then: 子ユーザーが ACTIVE に変更されて保存されること
            verify(userRepository).save(argThat(u ->
                    u.getStatus() == UserEntity.UserStatus.ACTIVE
            ));
            verify(parentalConsentLinkRepository).save(argThat(l ->
                    l.getStatus() == ParentalConsentLinkStatus.APPROVED
            ));
        }

        @Test
        @DisplayName("異常系: 無効なトークンでAUTH_060をスローすること")
        void approveParentalConsent_invalidToken_throwsAuth060() {
            // given
            given(authTokenService.hashToken(anyString())).willReturn("invalidHash");
            given(parentalConsentLinkRepository.findByTokenHash("invalidHash"))
                    .willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> parentalConsentService.approveParentalConsent("badToken", 2L, "127.0.0.1"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.AUTH_060);
        }

        @Test
        @DisplayName("異常系: 自己承認でAUTH_062をスローすること")
        void approveParentalConsent_selfApproval_throwsAuth062() {
            // given
            Long childUserId = 1L;
            ParentalConsentLinkEntity link = buildPendingLink(childUserId, null, "parent@example.com");
            given(authTokenService.hashToken(anyString())).willReturn("tokenHash123");
            given(parentalConsentLinkRepository.findByTokenHash("tokenHash123"))
                    .willReturn(Optional.of(link));

            // when / then: 保護者IDが子IDと同じ
            assertThatThrownBy(() -> parentalConsentService.approveParentalConsent("rawToken", childUserId, "127.0.0.1"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.AUTH_062);
        }

        @Test
        @DisplayName("異常系: 未成年保護者でAUTH_063をスローすること")
        void approveParentalConsent_minorParent_throwsAuth063() {
            // given
            Long childUserId = 1L;
            Long parentUserId = 2L;
            ParentalConsentLinkEntity link = buildPendingLink(childUserId, null, "parent@example.com");
            given(authTokenService.hashToken(anyString())).willReturn("tokenHash123");
            given(parentalConsentLinkRepository.findByTokenHash("tokenHash123"))
                    .willReturn(Optional.of(link));

            // 保護者が未成年（2015年生まれ）
            UserEntity minorParent = UserEntity.builder()
                    .email("parent@example.com")
                    .lastName("親")
                    .firstName("太郎")
                    .displayName("おやたろう")
                    .locale("ja")
                    .timezone("Asia/Tokyo")
                    .status(UserEntity.UserStatus.ACTIVE)
                    .isSearchable(true)
                    .birthDate("2015-06-01")
                    .build();
            given(userRepository.findById(parentUserId)).willReturn(Optional.of(minorParent));

            // when / then
            assertThatThrownBy(() -> parentalConsentService.approveParentalConsent("rawToken", parentUserId, "127.0.0.1"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.AUTH_063);
        }
    }

    // ========================================
    // rejectParentalConsent テスト
    // ========================================

    @Nested
    @DisplayName("rejectParentalConsent")
    class RejectParentalConsentTests {

        @Test
        @DisplayName("正常系: APPROVED保護者がいる場合は子アカウントが維持されること")
        void rejectParentalConsent_withApprovedParent_childAccountMaintained() {
            // given
            Long childUserId = 1L;
            ParentalConsentLinkEntity pendingLink = buildPendingLink(childUserId, 2L, "parent1@example.com");
            ParentalConsentLinkEntity approvedLink = buildApprovedLink(childUserId, 3L, "parent2@example.com");

            given(authTokenService.hashToken(anyString())).willReturn("tokenHash123");
            given(parentalConsentLinkRepository.findByTokenHash("tokenHash123"))
                    .willReturn(Optional.of(pendingLink));
            given(parentalConsentLinkRepository.findByChildUserId(childUserId))
                    .willReturn(List.of(pendingLink, approvedLink));
            given(emailOutboxService.enqueue(any())).willReturn(UUID.randomUUID());
            // 子ユーザーを返す（拒否通知メール用）
            UserEntity childUser = buildChildUser(childUserId, "child@example.com");
            given(userRepository.findById(childUserId)).willReturn(Optional.of(childUser));

            // when
            parentalConsentService.rejectParentalConsent("rawToken", "127.0.0.1");

            // then: 子ユーザーの論理削除は実行されないこと
            verify(userRepository, never()).save(argThat(u -> u.getDeletedAt() != null));
            verify(parentalConsentLinkRepository).save(argThat(l ->
                    l.getStatus() == ParentalConsentLinkStatus.REJECTED
            ));
        }

        @Test
        @DisplayName("正常系: APPROVED保護者がゼロになった場合は子アカウントが論理削除されること")
        void rejectParentalConsent_noApprovedParent_childAccountDeleted() {
            // given
            Long childUserId = 1L;
            ParentalConsentLinkEntity pendingLink = buildPendingLink(childUserId, 2L, "parent@example.com");

            given(authTokenService.hashToken(anyString())).willReturn("tokenHash123");
            given(parentalConsentLinkRepository.findByTokenHash("tokenHash123"))
                    .willReturn(Optional.of(pendingLink));
            // 拒否後: pendingLink が REJECTED に変化 → PENDING / APPROVED なし
            given(parentalConsentLinkRepository.findByChildUserId(childUserId))
                    .willReturn(List.of(pendingLink)); // status は reject() 後に REJECTED
            given(emailOutboxService.enqueue(any())).willReturn(UUID.randomUUID());

            UserEntity childUser = buildChildUser(childUserId, "child@example.com");
            given(userRepository.findById(childUserId)).willReturn(Optional.of(childUser));

            // when
            parentalConsentService.rejectParentalConsent("rawToken", "127.0.0.1");

            // then: 子ユーザーが論理削除されること
            verify(userRepository).save(argThat(u -> u.getDeletedAt() != null));
        }
    }

    // ========================================
    // checkWithdrawalBlock テスト
    // ========================================

    @Nested
    @DisplayName("checkWithdrawalBlock")
    class CheckWithdrawalBlockTests {

        @Test
        @DisplayName("正常系: 保護者でないユーザーは通過すること")
        void checkWithdrawalBlock_notGuardian_noException() {
            // given
            Long userId = 1L;
            given(parentalConsentLinkRepository.findByParentUserIdAndStatus(
                    userId, ParentalConsentLinkStatus.APPROVED)).willReturn(List.of());

            // when / then: 例外なし
            parentalConsentService.checkWithdrawalBlock(userId);
        }

        @Test
        @DisplayName("異常系: 唯一の保護者退会でAUTH_066をスローすること")
        void checkWithdrawalBlock_soleGuardian_throwsAuth066() {
            // given
            Long parentUserId = 2L;
            Long childUserId = 1L;
            ParentalConsentLinkEntity approvedLink = buildApprovedLink(childUserId, parentUserId, "parent@example.com");

            given(parentalConsentLinkRepository.findByParentUserIdAndStatus(
                    parentUserId, ParentalConsentLinkStatus.APPROVED)).willReturn(List.of(approvedLink));

            UserEntity childUser = buildChildUser(childUserId, "child@example.com");
            // 子ユーザーが PENDING_PARENTAL_CONSENT 状態
            given(userRepository.findById(childUserId)).willReturn(Optional.of(childUser));
            given(parentalConsentLinkRepository.countByChildUserIdAndStatus(
                    childUserId, ParentalConsentLinkStatus.APPROVED)).willReturn(1L);

            // when / then
            assertThatThrownBy(() -> parentalConsentService.checkWithdrawalBlock(parentUserId))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.AUTH_066);
        }

        @Test
        @DisplayName("正常系: 複数保護者がいる場合は通過すること")
        void checkWithdrawalBlock_multipleGuardians_noException() {
            // given
            Long parentUserId = 2L;
            Long childUserId = 1L;
            ParentalConsentLinkEntity approvedLink = buildApprovedLink(childUserId, parentUserId, "parent@example.com");

            given(parentalConsentLinkRepository.findByParentUserIdAndStatus(
                    parentUserId, ParentalConsentLinkStatus.APPROVED)).willReturn(List.of(approvedLink));

            UserEntity childUser = buildChildUser(childUserId, "child@example.com");
            given(userRepository.findById(childUserId)).willReturn(Optional.of(childUser));
            // 他にも保護者が存在する（2件以上）
            given(parentalConsentLinkRepository.countByChildUserIdAndStatus(
                    childUserId, ParentalConsentLinkStatus.APPROVED)).willReturn(2L);

            // when / then: 例外なし
            parentalConsentService.checkWithdrawalBlock(parentUserId);
        }
    }
}
