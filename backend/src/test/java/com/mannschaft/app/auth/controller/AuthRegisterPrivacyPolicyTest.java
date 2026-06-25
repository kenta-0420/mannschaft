package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthRegistrationService;
import com.mannschaft.app.auth.service.AuthService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import org.springframework.data.redis.core.ValueOperations;
import static org.mockito.Mockito.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * プライバシーポリシー同意フィールド — BE 受け入れテスト。
 *
 * <p>AC-1/AC-2: {@link AuthLoginController} への HTTP 層バリデーションテスト。
 * AC-3/AC-4: {@link AbstractMySqlIntegrationTest} を継承した永続化テスト。</p>
 *
 * <p>設計書: docs/features/F_privacy_policy.md §8</p>
 */
class AuthRegisterPrivacyPolicyTest {

    // ========================================================
    // AC-1 / AC-2 — HTTP層バリデーションテスト（@WebMvcTest）
    // ========================================================

    /**
     * {@link AuthLoginController} の POST /register バリデーション検証。
     *
     * <p>AC-1: {@code privacyPolicyAccepted=false} → 400
     * <br>AC-2: {@code privacyPolicyVersion} 空文字 → 400</p>
     */
    @WebMvcTest(AuthLoginController.class)
    @AutoConfigureMockMvc(addFilters = false)
    @DisplayName("AC-1/AC-2: プライバシーポリシー同意 — HTTPバリデーションテスト")
    static class PrivacyPolicyValidationWebMvcTest {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private AuthService authService;

        @MockitoBean
        private AuthTokenService authTokenService;

        @MockitoBean
        private UserLocaleCache userLocaleCache;

        @MockitoBean
        private ProxyInputConsentRepository proxyInputConsentRepository;

        @MockitoBean
        private ProxyInputContext proxyInputContext;

        @MockitoBean
        private AccessGuard accessGuard;

        /** 正常登録リクエストのベースボディ（privacyPolicy フィールドを上書きして使う）。 */
        private static final String VALID_BASE_BODY = """
                {
                  "email": "privacy-test@example.com",
                  "password": "Passw0rd!",
                  "lastName": "田中",
                  "firstName": "太郎",
                  "nickname": "taro",
                  "postalCode": "123-4567",
                  "birth_date": "1990-01-01",
                  "locale": "ja",
                  "timezone": "Asia/Tokyo",
                  "privacyPolicyAccepted": true,
                  "privacyPolicyVersion": "1.1.0"
                }
                """;

        @Test
        @DisplayName("AC-1: privacyPolicyAccepted=false で登録すると 400 を返す")
        void ac1_privacyPolicyNotAccepted_returns400() throws Exception {
            String body = """
                    {
                      "email": "pp-false@example.com",
                      "password": "Passw0rd!",
                      "lastName": "田中",
                      "firstName": "太郎",
                      "nickname": "taro",
                      "postalCode": "123-4567",
                      "birth_date": "1990-01-01",
                      "locale": "ja",
                      "timezone": "Asia/Tokyo",
                      "privacyPolicyAccepted": false,
                      "privacyPolicyVersion": "1.1.0"
                    }
                    """;

            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"))
                    .andExpect(jsonPath("$.error.fieldErrors[?(@.message == 'AUTH_PP_001')]").exists());
        }

        @Test
        @DisplayName("AC-2: privacyPolicyVersion 空文字で登録すると 400 を返す")
        void ac2_privacyPolicyVersionBlank_returns400() throws Exception {
            String body = """
                    {
                      "email": "pp-blank-ver@example.com",
                      "password": "Passw0rd!",
                      "lastName": "田中",
                      "firstName": "太郎",
                      "nickname": "taro",
                      "postalCode": "123-4567",
                      "birth_date": "1990-01-01",
                      "locale": "ja",
                      "timezone": "Asia/Tokyo",
                      "privacyPolicyAccepted": true,
                      "privacyPolicyVersion": ""
                    }
                    """;

            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"))
                    .andExpect(jsonPath("$.error.fieldErrors[?(@.field == 'privacyPolicyVersion')]").exists());
        }
    }

    // ========================================================
    // AC-3 / AC-4 — 永続化テスト（Testcontainers MySQL）
    // ========================================================

    /**
     * {@link AuthRegistrationService#register} を実際の MySQL に通して
     * privacy_policy_accepted_at / privacy_policy_version が保存されることを検証する。
     *
     * <p>AC-3: 正常登録後 {@code users.privacy_policy_accepted_at} が null でない。
     * <br>AC-4: 正常登録後 {@code users.privacy_policy_version} が送信値と一致する。</p>
     */
    @Transactional
    @EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
    @DisplayName("AC-3/AC-4: プライバシーポリシー同意 — 永続化テスト（Testcontainers MySQL）")
    static class PrivacyPolicyPersistenceIntegrationTest extends AbstractMySqlIntegrationTest {

        @Autowired
        private AuthRegistrationService authRegistrationService;

        @Autowired
        private UserRepository userRepository;

        @BeforeEach
        void setUpRedisMock() {
            // redisTemplate.opsForValue() は Mockito デフォルト null を返すため NPE になる。
            // checkRateLimit が DataAccessException 以外の例外をスローしないよう
            // opsForValue() -> increment() をスタブ化する。
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> ops = mock(ValueOperations.class);
            given(redisTemplate.opsForValue()).willReturn(ops);
            given(ops.increment(any())).willReturn(0L);
        }

        @Test
        @DisplayName("AC-3: 正常登録後 privacy_policy_accepted_at が null でない")
        void ac3_normalRegister_privacyPolicyAcceptedAtNotNull() {
            // given
            com.mannschaft.app.auth.dto.RegisterRequest req =
                    buildRegisterRequest("pp-test-ac3@example.com", "1.1.0");

            // when
            authRegistrationService.register(req, "127.0.0.1");

            // then
            Optional<UserEntity> saved = userRepository.findByEmail("pp-test-ac3@example.com");
            assertThat(saved).isPresent();
            assertThat(saved.get().getPrivacyPolicyAcceptedAt())
                    .as("privacy_policy_accepted_at は登録時に設定されるべき")
                    .isNotNull();
        }

        @Test
        @DisplayName("AC-4: 正常登録後 privacy_policy_version が送信値と一致する")
        void ac4_normalRegister_privacyPolicyVersionMatchesSentValue() {
            // given
            String expectedVersion = "1.1.0";
            com.mannschaft.app.auth.dto.RegisterRequest req =
                    buildRegisterRequest("pp-test-ac4@example.com", expectedVersion);

            // when
            authRegistrationService.register(req, "127.0.0.1");

            // then
            Optional<UserEntity> saved = userRepository.findByEmail("pp-test-ac4@example.com");
            assertThat(saved).isPresent();
            assertThat(saved.get().getPrivacyPolicyVersion())
                    .as("privacy_policy_version は送信値と一致するべき")
                    .isEqualTo(expectedVersion);
        }

        /**
         * テスト用の {@link com.mannschaft.app.auth.dto.RegisterRequest} を組み立てるヘルパー。
         */
        private com.mannschaft.app.auth.dto.RegisterRequest buildRegisterRequest(
                String email, String privacyPolicyVersion) {
            return new com.mannschaft.app.auth.dto.RegisterRequest(
                    email,
                    "Passw0rd!",
                    "テスト",
                    "ユーザー",
                    "testuser",
                    "123-4567",
                    "ja",
                    "Asia/Tokyo",
                    null,
                    "1990-01-01",
                    true,
                    privacyPolicyVersion
            );
        }
    }
}
