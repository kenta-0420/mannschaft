package com.mannschaft.app.auth.service;

import com.mannschaft.app.admin.service.BetaRestrictionService;
import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.dto.RegisterRequest;
import com.mannschaft.app.auth.entity.EmailVerificationTokenEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.EmailVerificationTokenRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.postal.CountryResolver;
import com.mannschaft.app.postal.PostalCodePolicyRegistry;
import com.mannschaft.app.role.service.InviteService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link AuthRegistrationService} の birth_date バリデーション / verifyEmail 未成年判定テスト。
 * F01.9 年齢確認・保護者同意機能 Wave 3A の新規テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthRegistrationService - F01.9 birth_date / 未成年判定")
class AuthRegistrationServiceBirthDateTest {

    @InjectMocks
    private AuthRegistrationService authRegistrationService;

    @Mock
    private UserRepository userRepository;
    @Mock
    private EmailVerificationTokenRepository emailVerificationTokenRepository;
    @Mock
    private AuthTokenService authTokenService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private DomainEventPublisher eventPublisher;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private EncryptionService encryptionService;
    @Mock
    private BetaRestrictionService betaRestrictionService;
    @Mock
    private InviteService inviteService;
    @Mock
    private CountryResolver countryResolver;
    @Mock
    private PostalCodePolicyRegistry postalCodePolicyRegistry;

    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_PASSWORD = "Password1!";
    private static final String TEST_IP = "127.0.0.1";

    private RegisterRequest buildRequest(String birthDate) {
        return new RegisterRequest(
                TEST_EMAIL, TEST_PASSWORD, "山田", "太郎", "yamada", null, "ja", "Asia/Tokyo", null, birthDate);
    }

    // ========================================
    // register - birth_date バリデーション
    // ========================================

    @Nested
    @DisplayName("register - birth_date バリデーション")
    class RegisterBirthDateValidation {

        @Test
        @DisplayName("異常系: birth_dateがnullでAUTH_050をスローすること")
        void register_birthDateNull_throwsAuth050() {
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(false);
            given(userRepository.findByEmailIncludingDeleted(TEST_EMAIL)).willReturn(Optional.empty());

            assertThatThrownBy(() -> authRegistrationService.register(buildRequest(null), TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.AUTH_050);
        }

        @Test
        @DisplayName("異常系: birth_dateが不正形式でAUTH_051をスローすること")
        void register_birthDateInvalidFormat_throwsAuth051() {
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(false);
            given(userRepository.findByEmailIncludingDeleted(TEST_EMAIL)).willReturn(Optional.empty());

            assertThatThrownBy(() -> authRegistrationService.register(buildRequest("20000101"), TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.AUTH_051);
        }

        @Test
        @DisplayName("異常系: birth_dateが未来日付でAUTH_052をスローすること")
        void register_birthDateFuture_throwsAuth052() {
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(false);
            given(userRepository.findByEmailIncludingDeleted(TEST_EMAIL)).willReturn(Optional.empty());

            assertThatThrownBy(() -> authRegistrationService.register(buildRequest("2099-12-31"), TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.AUTH_052);
        }

        @Test
        @DisplayName("異常系: birth_dateが100年以上前でAUTH_053をスローすること")
        void register_birthDateTooOld_throwsAuth053() {
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(false);
            given(userRepository.findByEmailIncludingDeleted(TEST_EMAIL)).willReturn(Optional.empty());

            assertThatThrownBy(() -> authRegistrationService.register(buildRequest("1900-01-01"), TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.AUTH_053);
        }
    }

    // ========================================
    // verifyEmail - 未成年判定
    // ========================================

    @Nested
    @DisplayName("verifyEmail - 未成年判定")
    class VerifyEmailMinorCheck {

        private EmailVerificationTokenEntity buildVerificationToken(Long userId) {
            return EmailVerificationTokenEntity.builder()
                    .userId(userId)
                    .tokenHash("hashedToken")
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build();
        }

        @Test
        @DisplayName("正常系: 18歳以上ならACTIVEになること")
        void verifyEmail_adult_becomesActive() {
            // given
            Long userId = 1L;
            String rawToken = "rawToken";
            given(authTokenService.hashToken(rawToken)).willReturn("hashedToken");
            given(emailVerificationTokenRepository.findByTokenHash("hashedToken"))
                    .willReturn(Optional.of(buildVerificationToken(userId)));

            // 成人ユーザー (1990年生まれ)
            UserEntity adultUser = UserEntity.builder()
                    .email(TEST_EMAIL)
                    .passwordHash("hash")
                    .lastName("山田")
                    .firstName("太郎")
                    .displayName("yamada")
                    .locale("ja")
                    .timezone("Asia/Tokyo")
                    .status(UserEntity.UserStatus.PENDING_VERIFICATION)
                    .isSearchable(true)
                    .birthDate("1990-06-15")
                    .build();
            given(userRepository.findById(userId)).willReturn(Optional.of(adultUser));

            // when
            authRegistrationService.verifyEmail(rawToken);

            // then: ACTIVE に遷移
            assertThat(adultUser.getStatus()).isEqualTo(UserEntity.UserStatus.ACTIVE);
        }

        @Test
        @DisplayName("正常系: 18歳未満ならPENDING_PARENTAL_CONSENTになること")
        void verifyEmail_minor_becomesPendingParentalConsent() {
            // given
            Long userId = 1L;
            String rawToken = "rawToken";
            given(authTokenService.hashToken(rawToken)).willReturn("hashedToken");
            given(emailVerificationTokenRepository.findByTokenHash("hashedToken"))
                    .willReturn(Optional.of(buildVerificationToken(userId)));

            // 未成年ユーザー (2015年生まれ)
            UserEntity minorUser = UserEntity.builder()
                    .email(TEST_EMAIL)
                    .passwordHash("hash")
                    .lastName("山田")
                    .firstName("次郎")
                    .displayName("jiro")
                    .locale("ja")
                    .timezone("Asia/Tokyo")
                    .status(UserEntity.UserStatus.PENDING_VERIFICATION)
                    .isSearchable(true)
                    .birthDate("2015-06-15")
                    .build();
            given(userRepository.findById(userId)).willReturn(Optional.of(minorUser));

            // when
            authRegistrationService.verifyEmail(rawToken);

            // then: PENDING_PARENTAL_CONSENT に遷移
            assertThat(minorUser.getStatus()).isEqualTo(UserEntity.UserStatus.PENDING_PARENTAL_CONSENT);
        }

        @Test
        @DisplayName("正常系: birthDateがnull（移行期旧アカウント）ならACTIVEになること")
        void verifyEmail_nullBirthDate_becomesActive() {
            // given
            Long userId = 1L;
            String rawToken = "rawToken";
            given(authTokenService.hashToken(rawToken)).willReturn("hashedToken");
            given(emailVerificationTokenRepository.findByTokenHash("hashedToken"))
                    .willReturn(Optional.of(buildVerificationToken(userId)));

            // birth_date なし（旧アカウント）
            UserEntity oldUser = UserEntity.builder()
                    .email(TEST_EMAIL)
                    .passwordHash("hash")
                    .lastName("山田")
                    .firstName("三郎")
                    .displayName("saburo")
                    .locale("ja")
                    .timezone("Asia/Tokyo")
                    .status(UserEntity.UserStatus.PENDING_VERIFICATION)
                    .isSearchable(true)
                    .birthDate(null)
                    .build();
            given(userRepository.findById(userId)).willReturn(Optional.of(oldUser));

            // when
            authRegistrationService.verifyEmail(rawToken);

            // then: ACTIVE に遷移（移行期の旧アカウントはそのまま通す）
            assertThat(oldUser.getStatus()).isEqualTo(UserEntity.UserStatus.ACTIVE);
        }
    }
}
