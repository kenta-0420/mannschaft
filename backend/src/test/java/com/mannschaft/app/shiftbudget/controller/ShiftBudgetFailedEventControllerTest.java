package com.mannschaft.app.shiftbudget.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventStatus;
import com.mannschaft.app.shiftbudget.dto.FailedEventResponse;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetFailedEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
 * {@link ShiftBudgetFailedEventController} の MockMvc 結合テスト（Phase 10-β）。
 *
 * <p>カバレッジ:</p>
 * <ul>
 *   <li>GET /failed-events → 200 + 配列形状（status 絞り込み有/無）</li>
 *   <li>POST /failed-events/{id}/retry → 200</li>
 *   <li>POST /failed-events/{id}/retry: 終端ステータス → 409</li>
 *   <li>POST /failed-events/{id}/retry: 別組織 → 404</li>
 *   <li>POST /failed-events/{id}/retry: 権限なし → 403</li>
 *   <li>POST /failed-events/{id}/resolve → 200</li>
 * </ul>
 *
 * <p>{@code ProxyInputConsentRepository} + {@code ProxyInputContext} を {@code @MockitoBean} で必須宣言
 * （引き継ぎメモ #7 のハマリポイント踏襲）。</p>
 */
@WebMvcTest(ShiftBudgetFailedEventController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ShiftBudgetFailedEventController 結合テスト")
class ShiftBudgetFailedEventControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 1L;
    private static final Long EVENT_ID = 50L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ShiftBudgetFailedEventService failedEventService;

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

    private FailedEventResponse sample() {
        return FailedEventResponse.builder()
                .id(EVENT_ID)
                .organizationId(ORG_ID)
                .eventType("THRESHOLD_ALERT")
                .sourceId(42L)
                .errorMessage("RuntimeException: test")
                .retryCount(1)
                .lastRetriedAt(LocalDateTime.of(2026, 5, 5, 10, 0))
                .status("PENDING")
                .createdAt(LocalDateTime.of(2026, 5, 5, 9, 0))
                .updatedAt(LocalDateTime.of(2026, 5, 5, 10, 0))
                .build();
    }

    @Test
    @DisplayName("GET /failed-events → 200 + 一覧 JSON 配列（status 未指定）")
    void list_200_全件() throws Exception {
        given(failedEventService.list(eq(ORG_ID), eq(null), anyInt(), anyInt()))
                .willReturn(List.of(sample()));

        mockMvc.perform(get("/api/v1/shift-budget/failed-events")
                        .header("X-Organization-Id", ORG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(EVENT_ID))
                .andExpect(jsonPath("$.data[0].event_type").value("THRESHOLD_ALERT"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("GET /failed-events?status=EXHAUSTED → 200 + 絞り込み配列")
    void list_200_status絞り込み() throws Exception {
        given(failedEventService.list(eq(ORG_ID),
                eq(ShiftBudgetFailedEventStatus.EXHAUSTED), anyInt(), anyInt()))
                .willReturn(List.of(sample()));

        mockMvc.perform(get("/api/v1/shift-budget/failed-events")
                        .header("X-Organization-Id", ORG_ID)
                        .param("status", "EXHAUSTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(EVENT_ID));
    }

    @Test
    @DisplayName("POST /failed-events/{id}/retry → 200")
    void retry_200() throws Exception {
        given(failedEventService.retry(eq(ORG_ID), eq(EVENT_ID))).willReturn(sample());

        mockMvc.perform(post("/api/v1/shift-budget/failed-events/" + EVENT_ID + "/retry")
                        .header("X-Organization-Id", ORG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(EVENT_ID));
    }

    @Test
    @DisplayName("POST retry: 終端ステータス → 409 (FAILED_EVENT_NOT_RETRIABLE)")
    void retry_409() throws Exception {
        willThrow(new BusinessException(ShiftBudgetErrorCode.FAILED_EVENT_NOT_RETRIABLE))
                .given(failedEventService).retry(eq(ORG_ID), eq(EVENT_ID));

        mockMvc.perform(post("/api/v1/shift-budget/failed-events/" + EVENT_ID + "/retry")
                        .header("X-Organization-Id", ORG_ID))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST retry: 別組織 → 404 (FAILED_EVENT_NOT_FOUND)")
    void retry_404() throws Exception {
        willThrow(new BusinessException(ShiftBudgetErrorCode.FAILED_EVENT_NOT_FOUND))
                .given(failedEventService).retry(eq(ORG_ID), eq(EVENT_ID));

        mockMvc.perform(post("/api/v1/shift-budget/failed-events/" + EVENT_ID + "/retry")
                        .header("X-Organization-Id", ORG_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST retry: BUDGET_ADMIN_REQUIRED → 403")
    void retry_403() throws Exception {
        willThrow(new BusinessException(ShiftBudgetErrorCode.BUDGET_ADMIN_REQUIRED))
                .given(failedEventService).retry(eq(ORG_ID), eq(EVENT_ID));

        mockMvc.perform(post("/api/v1/shift-budget/failed-events/" + EVENT_ID + "/retry")
                        .header("X-Organization-Id", ORG_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /failed-events/{id}/resolve → 200")
    void resolve_200() throws Exception {
        FailedEventResponse resolved = FailedEventResponse.builder()
                .id(EVENT_ID).organizationId(ORG_ID)
                .eventType("THRESHOLD_ALERT")
                .status("MANUAL_RESOLVED")
                .build();
        given(failedEventService.markManualResolved(eq(ORG_ID), eq(EVENT_ID)))
                .willReturn(resolved);

        mockMvc.perform(post("/api/v1/shift-budget/failed-events/" + EVENT_ID + "/resolve")
                        .header("X-Organization-Id", ORG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("MANUAL_RESOLVED"));
    }
}
