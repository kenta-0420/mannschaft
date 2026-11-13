package com.mannschaft.app.auth.service;

import com.mannschaft.app.admin.service.BetaRestrictionService;
import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.dto.RegisterRequest;
import com.mannschaft.app.auth.repository.EmailVerificationTokenRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.postal.CountryResolver;
import com.mannschaft.app.postal.PostalCodePolicyRegistry;
import com.mannschaft.app.role.service.InviteAcceptanceFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 同一メールの同時登録で、存在チェックと INSERT の間の競合により users.uq_users_email の
 * 一意制約違反が起きた場合に 500 ではなく AUTH_004 になることの検証（CMP-261113-0210）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthRegistrationService - メール重複の同時登録競合")
class AuthRegistrationServiceDuplicateEmailRaceTest {

    @InjectMocks
    private AuthRegistrationService service;

    @Mock private UserRepository userRepository;
    @Mock private EmailVerificationTokenRepository emailVerificationTokenRepository;
    @Mock private AuthTokenService authTokenService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private DomainEventPublisher eventPublisher;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private EncryptionService encryptionService;
    @Mock private BetaRestrictionService betaRestrictionService;
    @Mock private InviteAcceptanceFacade inviteAcceptanceFacade;
    @Mock private CountryResolver countryResolver;
    @Mock private PostalCodePolicyRegistry postalCodePolicyRegistry;

    private static final String EMAIL = "race@example.com";

    private RegisterRequest request() {
        String birth = LocalDate.now().minusYears(30).toString();
        return new RegisterRequest(
                EMAIL, "Password1!", "山田", "太郎", "yamada", null, "ja", "Asia/Tokyo", null, birth,
                true, "1.1.0");
    }

    private void passChecks() {
        given(userRepository.existsByEmail(EMAIL)).willReturn(false);
        given(userRepository.findByEmailIncludingDeleted(EMAIL)).willReturn(Optional.empty());
        given(passwordEncoder.encode(any())).willReturn("hash");
        given(countryResolver.resolve(any(), any())).willReturn(Optional.empty());
    }

    @Test
    @DisplayName("メール一意制約違反は AUTH_004 になる")
    void emailUniqueViolation_becomesAuth004() {
        passChecks();
        given(userRepository.save(any())).willThrow(new DataIntegrityViolationException(
                "could not execute statement; Duplicate entry 'race@example.com' for key 'users.uq_users_email'"));

        assertThatThrownBy(() -> service.register(request(), "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.AUTH_004);
    }

    @Test
    @DisplayName("他の制約違反は AUTH_004 に写像せずそのまま投げる")
    void otherViolation_isRethrown() {
        passChecks();
        DataIntegrityViolationException other = new DataIntegrityViolationException(
                "Duplicate entry 'x' for key 'users.uq_users_something_else'");
        given(userRepository.save(any())).willThrow(other);

        assertThatThrownBy(() -> service.register(request(), "127.0.0.1"))
                .isSameAs(other);
    }
}
