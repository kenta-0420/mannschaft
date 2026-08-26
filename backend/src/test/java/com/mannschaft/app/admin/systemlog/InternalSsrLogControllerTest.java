package com.mannschaft.app.admin.systemlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link InternalSsrLogController} のコントローラーテスト。
 * 正しいトークンで 202、誤ったトークンで 403 を返すことを検証する。
 */
@DisplayName("InternalSsrLogController テスト")
@WebMvcTest(InternalSsrLogController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {
        "mannschaft.system-log.internal-token=test-secret-token"
})
class InternalSsrLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SystemLogService systemLogService;

    @MockitoBean
    private SystemLogPiiMasker systemLogPiiMasker;

    // WebMvcTest コンテキストで必要な依存解決用 Mock
    @MockitoBean
    private AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    private static final String VALID_TOKEN = "test-secret-token";
    private static final String INVALID_TOKEN = "wrong-token";

    private SsrErrorRequest buildRequest() {
        return new SsrErrorRequest(
                "error",
                "TypeError: Cannot read properties of null",
                "at Component.setup (/app/.output/server/chunks/build/component.mjs:123:10)",
                "/dashboard",
                "2026-05-09T01:00:00.000Z",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)"
        );
    }

    @Test
    @DisplayName("正しいトークンで 202 Accepted を返す")
    void postSsrLog_withValidToken_returns202() throws Exception {
        // PII マスキングの戻り値をセットアップ
        org.mockito.BDDMockito.given(systemLogPiiMasker.mask(org.mockito.ArgumentMatchers.anyString()))
                .willAnswer(inv -> inv.getArgument(0));
        doNothing().when(systemLogService).appendSsrError(any());

        mockMvc.perform(post("/api/internal/ssr-logs")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest())))
                .andExpect(status().isAccepted());

        verify(systemLogService).appendSsrError(any());
    }

    @Test
    @DisplayName("誤ったトークンで 403 Forbidden を返す")
    void postSsrLog_withInvalidToken_returns403() throws Exception {
        mockMvc.perform(post("/api/internal/ssr-logs")
                        .header("X-Internal-Token", INVALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest())))
                .andExpect(status().isForbidden());

        verify(systemLogService, never()).appendSsrError(any());
    }

    @Test
    @DisplayName("トークンヘッダーがない場合は 403 Forbidden を返す")
    void postSsrLog_withoutToken_returns403() throws Exception {
        mockMvc.perform(post("/api/internal/ssr-logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest())))
                .andExpect(status().isForbidden());

        verify(systemLogService, never()).appendSsrError(any());
    }
}
