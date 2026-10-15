package com.mannschaft.app.auth;

import com.mannschaft.app.auth.controller.Auth2faController;
import com.mannschaft.app.auth.dto.BackupCodesResponse;
import com.mannschaft.app.auth.dto.TotpSetupResponse;
import com.mannschaft.app.auth.service.Auth2faService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link Auth2faController} の結合テスト。
 * {@code @WebMvcTest} でコントローラー層のみをロードし、Service は MockitoBean で差し替える。
 *
 * <p>F08.9 P3b: 後見切替セッション中（acting-as / {@code isProxy()==true}）の
 * 認証クリティカル操作（TOTP設定・検証・バックアップコード再生成）が 403 で拒否されることを検証する。</p>
 */
@WebMvcTest(Auth2faController.class)
@Import(com.mannschaft.app.auth.guardianship.AuthenticationCriticalOperationGuard.class)
@AutoConfigureMockMvc(addFilters = false)
class Auth2faControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private Auth2faService auth2faService;

    @MockitoBean
    private com.mannschaft.app.auth.service.AuthTokenService authTokenService;

    // F11.3: UserLocaleFilter の依存解決用
    @MockitoBean
    private UserLocaleCache userLocaleCache;

    // F14.1: ProxyInputContextFilter の依存解決用
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUpSecurityContext() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("1", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Nested
    @DisplayName("通常入力（本人操作）— 正常系")
    class NotActingAs {

        @BeforeEach
        void notProxy() {
            given(proxyInputContext.isProxy()).willReturn(false);
        }

        @Test
        @DisplayName("POST /setup — 本人操作なら 201")
        void setupTotp_self_returns201() throws Exception {
            given(auth2faService.setupTotp(anyLong()))
                    .willReturn(ApiResponse.of(new TotpSetupResponse("SECRET", "otpauth://totp/x")));

            mockMvc.perform(post("/api/v1/auth/2fa/setup"))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("POST /backup-codes/regenerate — 本人操作なら 200")
        void regenerateBackupCodes_self_returns200() throws Exception {
            given(auth2faService.regenerateBackupCodes(anyLong()))
                    .willReturn(ApiResponse.of(new BackupCodesResponse(List.of("a", "b"))));

            mockMvc.perform(post("/api/v1/auth/2fa/backup-codes/regenerate"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("POST /verify — 本人操作なら 200（2FA設定検証・有効化）")
        void verifyTotpSetup_self_returns200() throws Exception {
            given(auth2faService.verifyTotpSetup(anyLong(), org.mockito.ArgumentMatchers.anyString()))
                    .willReturn(ApiResponse.of(new BackupCodesResponse(List.of("code1", "code2"))));

            String body = """
                    {
                      "totpCode": "123456"
                    }
                    """;

            mockMvc.perform(post("/api/v1/auth/2fa/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("F08.9 P3b — 後見切替中の認証クリティカル操作ガード")
    class GuardianshipActingAsGuard {

        @BeforeEach
        void actingAs() {
            given(proxyInputContext.isProxy()).willReturn(true);
        }

        @Test
        @DisplayName("POST /setup — 切替中は 403（2FA設定を代理不可）")
        void setupTotp_actingAs_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/auth/2fa/setup"))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(auth2faService);
        }

        @Test
        @DisplayName("POST /verify — 切替中は 403（2FA設定検証を代理不可）")
        void verifyTotpSetup_actingAs_returns403() throws Exception {
            String body = """
                    {
                      "totpCode": "123456"
                    }
                    """;

            mockMvc.perform(post("/api/v1/auth/2fa/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(auth2faService);
        }

        @Test
        @DisplayName("POST /backup-codes/regenerate — 切替中は 403（バックアップコード再生成を代理不可）")
        void regenerateBackupCodes_actingAs_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/auth/2fa/backup-codes/regenerate"))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(auth2faService);
        }
    }
}
