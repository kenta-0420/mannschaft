package com.mannschaft.app.security;

import com.mannschaft.app.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SecurityConfig} が本番の {@code @EnableMethodSecurity} により {@code @PreAuthorize} を
 * 実際に発火させることを固定する characterization test。
 *
 * <p>このテスト自身は {@code @EnableMethodSecurity} を宣言しない。したがって本番設定から同注釈が
 * 外れた場合、下記 Controller のメソッド認可が迂回され、期待する 403 にならず失敗する。</p>
 */
@SpringBootTest(
        classes = MethodSecurityIgnitionTest.TestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("SecurityConfig のメソッド認可起動")
class MethodSecurityIgnitionTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = "SYSTEM_ADMIN")
    @DisplayName("SYSTEM_ADMIN は @PreAuthorize 保護エンドポイントに到達できる")
    void systemAdmin_protectedEndpoint_returns2xx() throws Exception {
        mockMvc.perform(get("/api/v1/method-security-ignition/system-admin"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    @DisplayName("一般ユーザーは @PreAuthorize 保護エンドポイントで 403 となる")
    void member_protectedEndpoint_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/method-security-ignition/system-admin"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("未認証ユーザーは保護エンドポイントで 401 となる")
    void unauthenticated_protectedEndpoint_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/method-security-ignition/system-admin"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("accessGuard SpEL は許可済みスコープのみ通す")
    void scopeAdmin_allowedScope_returns2xx() throws Exception {
        mockMvc.perform(get("/api/v1/method-security-ignition/scopes/10"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("accessGuard SpEL は他スコープを 403 にする")
    void scopeAdmin_otherScope_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/method-security-ignition/scopes/11"))
                .andExpect(status().isForbidden());
    }

    @Configuration
    @Import(AuthorizationIntegrationTest.MinimalSecurityConfig.class)
    static class TestConfiguration {

        @Bean("accessGuard")
        TestAccessGuard accessGuard() {
            return new TestAccessGuard();
        }

        @Bean
        IgnitionController ignitionController() {
            return new IgnitionController();
        }
    }

    public static class TestAccessGuard {

        public boolean isScopeAdmin(Authentication authentication, Long scopeId, String scopeType) {
            return authentication != null
                    && "200".equals(authentication.getName())
                    && Long.valueOf(10L).equals(scopeId)
                    && "TEAM".equals(scopeType);
        }
    }

    @RestController
    public static class IgnitionController {

        @GetMapping("/api/v1/method-security-ignition/system-admin")
        @PreAuthorize("hasRole('SYSTEM_ADMIN')")
        public ResponseEntity<Void> systemAdmin() {
            return ResponseEntity.ok().build();
        }

        @GetMapping("/api/v1/method-security-ignition/scopes/{scopeId}")
        @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #scopeId, 'TEAM')")
        public ResponseEntity<Void> scopeAdmin(@PathVariable("scopeId") Long scopeId) {
            return ResponseEntity.ok().build();
        }
    }
}
