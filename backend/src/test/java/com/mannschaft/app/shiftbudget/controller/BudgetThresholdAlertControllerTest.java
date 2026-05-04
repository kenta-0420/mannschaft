package com.mannschaft.app.shiftbudget.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.dto.AlertResponse;
import com.mannschaft.app.shiftbudget.service.BudgetThresholdAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link BudgetThresholdAlertController} の MockMvc 結合テスト（Phase 9-δ 第2段、API #9 / #10）。
 *
 * <p>カバレッジ:</p>
 * <ul>
 *   <li>GET /alerts → 200 + 配列形状</li>
 *   <li>POST /alerts/{id}/acknowledge → 200 + acknowledged フィールド</li>
 *   <li>POST /alerts/{id}/acknowledge: 別組織 → 404 (ALERT_NOT_FOUND)</li>
 *   <li>POST /alerts/{id}/acknowledge: 権限なし → 403 (BUDGET_ADMIN_REQUIRED)</li>
 *   <li>POST /alerts/{id}/acknowledge: comment 500 文字超 → 400 (Bean Validation)</li>
 * </ul>
 *
 * <p>{@code ProxyInputConsentRepository} + {@code ProxyInputContext} を {@code @MockitoBean} で必須宣言
 * （引き継ぎメモ #7 のハマリポイント踏襲）。</p>
 */
@WebMvcTest(BudgetThresholdAlertController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("BudgetThresholdAlertController 結合テスト")
class BudgetThresholdAlertControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 1L;
    private static final Long ALERT_ID = 50L;
    private static final Long ALLOCATION_ID = 42L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BudgetThresholdAlertService alertService;

    @MockitoBean
    private AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    private AlertResponse sampleAlert() {
        return AlertResponse.builder()
                .id(ALERT_ID)
                .allocationId(ALLOCATION_ID)
                .thresholdPercent(80)
                .triggeredAt(LocalDateTime.of(2026, 6, 25, 14, 30))
                .consumedAmountAtTrigger(new BigDecimal("80000"))
                .workflowRequestId(null)
                .acknowledgedAt(null)
                .acknowledgedBy(null)
                .build();
    }

    @Test
    @DisplayName("GET /alerts → 200 + 一覧 JSON 配列")
    void list_200() throws Exception {
        given(alertService.list(eq(ORG_ID), anyInt(), anyInt()))
                .willReturn(List.of(sampleAlert()));

        mockMvc.perform(get("/api/v1/shift-budget/alerts")
                        .header("X-Organization-Id", ORG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(ALERT_ID))
                .andExpect(jsonPath("$.data[0].allocation_id").value(ALLOCATION_ID))
                .andExpect(jsonPath("$.data[0].threshold_percent").value(80));
    }

    @Test
    @DisplayName("POST /alerts/{id}/acknowledge → 200 + acknowledged_by/at セット")
    void acknowledge_200() throws Exception {
        AlertResponse acked = AlertResponse.builder()
                .id(ALERT_ID).allocationId(ALLOCATION_ID).thresholdPercent(80)
                .triggeredAt(LocalDateTime.of(2026, 6, 25, 14, 30))
                .consumedAmountAtTrigger(new BigDecimal("80000"))
                .acknowledgedAt(LocalDateTime.of(2026, 6, 25, 15, 0))
                .acknowledgedBy(USER_ID)
                .build();
        given(alertService.acknowledge(eq(ORG_ID), eq(ALERT_ID), any()))
                .willReturn(acked);

        mockMvc.perform(post("/api/v1/shift-budget/alerts/" + ALERT_ID + "/acknowledge")
                        .header("X-Organization-Id", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"了解、来月予算追加\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(ALERT_ID))
                .andExpect(jsonPath("$.data.acknowledged_by").value(USER_ID));
    }

    @Test
    @DisplayName("POST acknowledge: ALERT_NOT_FOUND → 404")
    void acknowledge_404() throws Exception {
        willThrow(new BusinessException(ShiftBudgetErrorCode.ALERT_NOT_FOUND))
                .given(alertService).acknowledge(eq(ORG_ID), eq(ALERT_ID), any());

        mockMvc.perform(post("/api/v1/shift-budget/alerts/" + ALERT_ID + "/acknowledge")
                        .header("X-Organization-Id", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST acknowledge: BUDGET_ADMIN_REQUIRED → 403")
    void acknowledge_403() throws Exception {
        willThrow(new BusinessException(ShiftBudgetErrorCode.BUDGET_ADMIN_REQUIRED))
                .given(alertService).acknowledge(eq(ORG_ID), eq(ALERT_ID), any());

        mockMvc.perform(post("/api/v1/shift-budget/alerts/" + ALERT_ID + "/acknowledge")
                        .header("X-Organization-Id", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST acknowledge: comment 501 文字 → 400 (Bean Validation)")
    void acknowledge_comment長すぎ400() throws Exception {
        String longComment = "あ".repeat(501);
        String body = objectMapper.writeValueAsString(
                java.util.Map.of("comment", longComment));

        mockMvc.perform(post("/api/v1/shift-budget/alerts/" + ALERT_ID + "/acknowledge")
                        .header("X-Organization-Id", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
