package com.mannschaft.app.common.pdf.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F12.1 §5.14 / F09.15 §9.4 — {@link PdfSignatureVerifyController} の MockMvc 結合テスト。
 */
@WebMvcTest(PdfSignatureVerifyController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PdfSignatureVerifyController 結合テスト")
class PdfSignatureVerifyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PdfSignatureVerifyService verifyService;

    @MockitoBean
    private AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    @AfterEach
    void tearDownSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void setAuthorityRole(String role) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "1", null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Nested
    @DisplayName("POST /api/v1/pdf-signatures/verify")
    class VerifyEndpoint {

        @Test
        @DisplayName("正常系: ADMIN ロール + 有効リクエスト → 200 + valid=true")
        void ADMIN_有効リクエスト_200() throws Exception {
            setAuthorityRole("ADMIN");

            PdfSignatureVerifyResponse mockResult = new PdfSignatureVerifyResponse(
                    true, true, true, "a".repeat(64), Instant.parse("2026-05-09T10:00:00Z"));
            given(verifyService.verify(any(PdfSignatureVerifyRequest.class))).willReturn(mockResult);

            PdfSignatureVerifyRequest req = new PdfSignatureVerifyRequest(
                    "covenant-uuid-100",
                    "ZHVtbXk=", // "dummy" の Base64
                    "a".repeat(64),
                    "abc123.1700000000000");

            mockMvc.perform(post("/api/v1/pdf-signatures/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.valid").value(true))
                    .andExpect(jsonPath("$.data.hashMatch").value(true))
                    .andExpect(jsonPath("$.data.tokenValid").value(true));
        }

        @Test
        @DisplayName("正常系: ADMIN + 改ざん検知 → 200 + valid=false")
        void ADMIN_改ざん検知_200_validFalse() throws Exception {
            setAuthorityRole("ADMIN");

            PdfSignatureVerifyResponse mockResult = new PdfSignatureVerifyResponse(
                    false, false, true, "b".repeat(64), Instant.parse("2026-05-09T10:00:00Z"));
            given(verifyService.verify(any(PdfSignatureVerifyRequest.class))).willReturn(mockResult);

            PdfSignatureVerifyRequest req = new PdfSignatureVerifyRequest(
                    "covenant-uuid-200",
                    "ZHVtbXk=",
                    "a".repeat(64),
                    "abc123.1700000000000");

            mockMvc.perform(post("/api/v1/pdf-signatures/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.valid").value(false))
                    .andExpect(jsonPath("$.data.hashMatch").value(false));
        }

        @Test
        @DisplayName("異常系: subjectId 未入力 → 400 (Bean Validation)")
        void subjectId未入力_400() throws Exception {
            setAuthorityRole("ADMIN");

            PdfSignatureVerifyRequest req = new PdfSignatureVerifyRequest(
                    "", "ZHVtbXk=", "a".repeat(64), "abc.123");

            mockMvc.perform(post("/api/v1/pdf-signatures/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("異常系: pdfBase64 未入力 → 400")
        void pdfBase64未入力_400() throws Exception {
            setAuthorityRole("ADMIN");

            PdfSignatureVerifyRequest req = new PdfSignatureVerifyRequest(
                    "subj", "", "a".repeat(64), "abc.123");

            mockMvc.perform(post("/api/v1/pdf-signatures/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());
        }
    }
}
