package com.mannschaft.app.jobmatching.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.jobmatching.controller.dto.IssueQrTokenRequest;
import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import com.mannschaft.app.jobmatching.entity.JobQrTokenEntity;
import com.mannschaft.app.jobmatching.enums.JobCheckInType;
import com.mannschaft.app.jobmatching.exception.JobmatchingErrorCode;
import com.mannschaft.app.jobmatching.policy.JobPolicy;
import com.mannschaft.app.jobmatching.repository.JobContractRepository;
import com.mannschaft.app.jobmatching.service.JobQrTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link JobQrTokenController} の MockMvc 結合テスト（F13.1 Phase 13.1.2）。
 *
 * <p>以下を検証する:</p>
 * <ul>
 *   <li>POST /api/v1/contracts/{id}/qr-tokens — 発行成功 / 権限外 403 / 契約不在 404</li>
 *   <li>GET /api/v1/contracts/{id}/qr-tokens/current — 取得成功 / 空 204 / 権限外 403</li>
 * </ul>
 */
@WebMvcTest(JobQrTokenController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("JobQrTokenController 結合テスト")
class JobQrTokenControllerTest {

    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 999L;
    private static final Long CONTRACT_ID = 5001L;
    private static final Long WORKER_ID = 200L;
    private static final Instant ISSUED_AT = Instant.parse("2026-06-01T10:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-06-01T10:01:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JobQrTokenService qrTokenService;

    @MockitoBean
    private JobContractRepository contractRepository;

    @MockitoBean
    private JobPolicy jobPolicy;

    // JwtAuthenticationFilter の依存解決用
    @MockitoBean
    private AuthTokenService authTokenService;

    // UserLocaleFilter の依存解決用
    @MockitoBean
    private UserLocaleCache userLocaleCache;

    // F14.1: ProxyInputContextFilter の依存解決用（@WebMvcTest コンテキストで必要）
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;

    @BeforeEach
    void setUpSecurityContext() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * Requester が USER_ID である契約を返す MOCK 設定。
     */
    private JobContractEntity setupContractForUser(Long requesterId) {
        JobContractEntity contract = JobContractEntity.builder()
                .jobPostingId(1L)
                .jobApplicationId(1L)
                .requesterUserId(requesterId)
                .workerUserId(WORKER_ID)
                .build();
        given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract));
        return contract;
    }

    // ═══════════════════════════════════════════════════════════════
    // POST /api/v1/contracts/{contractId}/qr-tokens
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/contracts/{id}/qr-tokens")
    class IssueToken {

        @Test
        @DisplayName("正常系: 201 Created + token/shortCode/type/issuedAt/expiresAt/kid が返る")
        void issue_正常系_201() throws Exception {
            JobContractEntity contract = setupContractForUser(USER_ID);
            given(jobPolicy.canIssueQrToken(contract, USER_ID)).willReturn(true);
            given(qrTokenService.issue(eq(CONTRACT_ID), eq(JobCheckInType.IN), eq(60), eq(USER_ID)))
                    .willReturn(new JobQrTokenService.IssueResult(
                            "jwt-string", "AB23CD", JobCheckInType.IN,
                            ISSUED_AT, EXPIRES_AT, "nonce-xyz", "v1"));

            IssueQrTokenRequest req = new IssueQrTokenRequest(JobCheckInType.IN, 60);

            mockMvc.perform(post("/api/v1/contracts/{contractId}/qr-tokens", CONTRACT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.token").value("jwt-string"))
                    .andExpect(jsonPath("$.data.shortCode").value("AB23CD"))
                    .andExpect(jsonPath("$.data.type").value("IN"))
                    .andExpect(jsonPath("$.data.kid").value("v1"));
        }

        @Test
        @DisplayName("権限なし（Requester 本人以外）: 403 JOB_PERMISSION_DENIED")
        void issue_権限外_403() throws Exception {
            JobContractEntity contract = setupContractForUser(OTHER_USER_ID);
            given(jobPolicy.canIssueQrToken(contract, USER_ID)).willReturn(false);

            IssueQrTokenRequest req = new IssueQrTokenRequest(JobCheckInType.IN, null);

            mockMvc.perform(post("/api/v1/contracts/{contractId}/qr-tokens", CONTRACT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("JOB_PERMISSION_DENIED"));
        }

        @Test
        @DisplayName("契約不在: 404 JOB_CONTRACT_NOT_FOUND")
        void issue_契約不在_404() throws Exception {
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.empty());

            IssueQrTokenRequest req = new IssueQrTokenRequest(JobCheckInType.IN, null);

            mockMvc.perform(post("/api/v1/contracts/{contractId}/qr-tokens", CONTRACT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("JOB_CONTRACT_NOT_FOUND"));
        }

        @Test
        @DisplayName("Service が JOB_CONTRACT_NOT_FOUND を投げるケースも 404 に載る")
        void issue_service側例外_エラーマップ経由() throws Exception {
            JobContractEntity contract = setupContractForUser(USER_ID);
            given(jobPolicy.canIssueQrToken(contract, USER_ID)).willReturn(true);
            given(qrTokenService.issue(any(), any(), any(), any()))
                    .willThrow(new BusinessException(JobmatchingErrorCode.JOB_CONTRACT_NOT_FOUND));

            IssueQrTokenRequest req = new IssueQrTokenRequest(JobCheckInType.OUT, 60);

            mockMvc.perform(post("/api/v1/contracts/{contractId}/qr-tokens", CONTRACT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("JOB_CONTRACT_NOT_FOUND"));
        }

        @Test
        @DisplayName("バリデーション: type 欠落は 400")
        void issue_type欠落_400() throws Exception {
            String badBody = "{\"ttlSeconds\":60}";

            mockMvc.perform(post("/api/v1/contracts/{contractId}/qr-tokens", CONTRACT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(badBody))
                    .andExpect(status().isBadRequest());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // GET /api/v1/contracts/{contractId}/qr-tokens/current
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/contracts/{id}/qr-tokens/current")
    class GetCurrent {

        @Test
        @DisplayName("正常系: 200 OK + shortCode/expiresAt 等が返る（token は null）")
        void getCurrent_正常系_200() throws Exception {
            JobContractEntity contract = setupContractForUser(USER_ID);
            given(jobPolicy.canIssueQrToken(contract, USER_ID)).willReturn(true);

            JobQrTokenEntity entity = JobQrTokenEntity.builder()
                    .jobContractId(CONTRACT_ID)
                    .type(JobCheckInType.IN)
                    .nonce("nonce-xyz")
                    .kid("v1")
                    .shortCode("AB23CD")
                    .issuedAt(ISSUED_AT)
                    .expiresAt(EXPIRES_AT)
                    .issuedByUserId(USER_ID)
                    .build();
            given(qrTokenService.getCurrent(CONTRACT_ID, JobCheckInType.IN, USER_ID))
                    .willReturn(Optional.of(entity));

            mockMvc.perform(get("/api/v1/contracts/{contractId}/qr-tokens/current", CONTRACT_ID)
                            .param("type", "IN"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.token").doesNotExist())
                    .andExpect(jsonPath("$.data.shortCode").value("AB23CD"))
                    .andExpect(jsonPath("$.data.kid").value("v1"))
                    .andExpect(jsonPath("$.data.type").value("IN"));
        }

        @Test
        @DisplayName("該当トークン無し: 204 No Content")
        void getCurrent_該当無し_204() throws Exception {
            JobContractEntity contract = setupContractForUser(USER_ID);
            given(jobPolicy.canIssueQrToken(contract, USER_ID)).willReturn(true);
            given(qrTokenService.getCurrent(CONTRACT_ID, JobCheckInType.OUT, USER_ID))
                    .willReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/contracts/{contractId}/qr-tokens/current", CONTRACT_ID)
                            .param("type", "OUT"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("権限なし（Requester 本人以外）: 403 JOB_PERMISSION_DENIED")
        void getCurrent_権限外_403() throws Exception {
            JobContractEntity contract = setupContractForUser(OTHER_USER_ID);
            given(jobPolicy.canIssueQrToken(contract, USER_ID)).willReturn(false);

            mockMvc.perform(get("/api/v1/contracts/{contractId}/qr-tokens/current", CONTRACT_ID)
                            .param("type", "IN"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("JOB_PERMISSION_DENIED"));
        }

        @Test
        @DisplayName("契約不在: 404 JOB_CONTRACT_NOT_FOUND")
        void getCurrent_契約不在_404() throws Exception {
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/contracts/{contractId}/qr-tokens/current", CONTRACT_ID)
                            .param("type", "IN"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("JOB_CONTRACT_NOT_FOUND"));
        }
    }
}
