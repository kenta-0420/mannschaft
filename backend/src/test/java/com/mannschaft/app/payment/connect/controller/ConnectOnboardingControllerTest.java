package com.mannschaft.app.payment.connect.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.payment.connect.ConnectAccountService;
import com.mannschaft.app.payment.connect.OnboardingStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.connect.dto.ConnectStatusResponse;
import com.mannschaft.app.payment.connect.dto.OnboardingLinkRequest;
import com.mannschaft.app.payment.connect.dto.OnboardingLinkResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Connect onboarding コントローラー軽量結合テスト（T10 PCI 禁則 / 契約）。
 *
 * <p>StandaloneSetup + Mockito で Service をモックし、HTTP 入出力（camelCase）と
 * PCI 禁則（公開レスポンスに {@code client_secret}/{@code pi_} を含めない・設計書 03 §4）を検証する。
 * #1232 前科に倣い、モック stub の引数個数を Controller の実呼び出しに一致させる。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectOnboardingController 契約テスト（T10）")
class ConnectOnboardingControllerTest {

    @Mock private ConnectAccountService connectAccountService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        ConnectOnboardingController controller = new ConnectOnboardingController(connectAccountService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();
    }

    @Test
    @DisplayName("POST onboarding-link: 200・camelCase・acct は本人レスポンスに含むが client_secret/pi_ は含まない（T10）")
    void onboardingLink_pciSafe() throws Exception {
        OnboardingLinkResponse response = new OnboardingLinkResponse(
                UUID.fromString("019607a0-0000-7000-8000-000000000020"),
                "acct_xxx",
                OnboardingStatus.ONBOARDING,
                "https://connect.stripe.com/setup/x",
                LocalDateTime.of(2026, 6, 2, 12, 0));
        // 引数個数を Controller の呼び出し（request 1 個）に一致させる
        given(connectAccountService.createOnboardingLink(any(OnboardingLinkRequest.class)))
                .willReturn(response);

        String body = """
                {"scopeKind":"USER","returnUrl":"https://app/return","refreshUrl":"https://app/refresh"}
                """;

        mockMvc.perform(post("/api/v1/payment/connect/onboarding-link")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboardingStatus").value("ONBOARDING"))
                .andExpect(jsonPath("$.data.onboardingUrl").value("https://connect.stripe.com/setup/x"))
                // PCI 禁則: 決済トークンを公開レスポンスに含めない
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("client_secret"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("clientSecret"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("\"pi_"))));
    }

    @Test
    @DisplayName("GET status: 200・camelCase・client_secret/pi_ を含まない（T10）")
    void status_pciSafe() throws Exception {
        ConnectStatusResponse response = new ConnectStatusResponse(
                UUID.fromString("019607a0-0000-7000-8000-000000000021"),
                ScopeKind.TEAM, 123L, OnboardingStatus.READY, true, true, List.of());
        given(connectAccountService.getStatus(any(), any())).willReturn(response);

        mockMvc.perform(get("/api/v1/payment/connect/status")
                        .param("scopeKind", "TEAM").param("scopeId", "123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboardingStatus").value("READY"))
                .andExpect(jsonPath("$.data.chargesEnabled").value(true))
                .andExpect(jsonPath("$.data.payoutsEnabled").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("client_secret"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("\"pi_"))));
    }
}
