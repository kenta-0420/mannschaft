package com.mannschaft.app.shiftbudget.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.service.MonthlyShiftBudgetCloseService;
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

import java.time.YearMonth;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MonthlyShiftBudgetCloseController} の MockMvc 結合テスト（Phase 9-δ 第2段、API #11）。
 *
 * <p>カバレッジ:</p>
 * <ul>
 *   <li>POST /monthly-close 正常系 → 200 + closed_allocations/already_closed フィールド</li>
 *   <li>year_month 形式違反 ("2026-13") → 400 (Bean Validation)</li>
 *   <li>権限なし → 403 (BUDGET_ADMIN_REQUIRED)</li>
 *   <li>重複実行 → 409 (MONTHLY_ALREADY_CLOSED) — Service が CloseResult で表現するため
 *       実際には Service 内で握りつぶされるが、外側のシナリオが BusinessException を出した場合の確認</li>
 * </ul>
 *
 * <p>{@code ProxyInputConsentRepository} + {@code ProxyInputContext} を {@code @MockitoBean} で必須宣言。</p>
 */
@WebMvcTest(MonthlyShiftBudgetCloseController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("MonthlyShiftBudgetCloseController 結合テスト")
class MonthlyShiftBudgetCloseControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MonthlyShiftBudgetCloseService closeService;

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

    @Test
    @DisplayName("POST /monthly-close 正常系 → 200 + closed_allocations 1, closed_consumptions 5")
    void close_200() throws Exception {
        given(closeService.close(eq(ORG_ID), eq(YearMonth.of(2026, 6))))
                .willReturn(new MonthlyShiftBudgetCloseService.CloseResult(1, 0, 5));

        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "organization_id", ORG_ID,
                "year_month", "2026-06"));

        mockMvc.perform(post("/api/v1/shift-budget/monthly-close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.organization_id").value(ORG_ID))
                .andExpect(jsonPath("$.data.year_month").value("2026-06"))
                .andExpect(jsonPath("$.data.closed_allocations").value(1))
                .andExpect(jsonPath("$.data.already_closed_allocations").value(0))
                .andExpect(jsonPath("$.data.closed_consumptions").value(5));
    }

    @Test
    @DisplayName("year_month が不正形式 ('2026-13') → 400 (Bean Validation)")
    void close_year_month不正形式_400() throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "organization_id", ORG_ID,
                "year_month", "2026-13"));

        mockMvc.perform(post("/api/v1/shift-budget/monthly-close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("権限なし → 403 (BUDGET_ADMIN_REQUIRED)")
    void close_権限なし_403() throws Exception {
        willThrow(new BusinessException(ShiftBudgetErrorCode.BUDGET_ADMIN_REQUIRED))
                .given(closeService).close(any(), any());

        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "organization_id", ORG_ID,
                "year_month", "2026-06"));

        mockMvc.perform(post("/api/v1/shift-budget/monthly-close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("重複実行 → 409 (MONTHLY_ALREADY_CLOSED) — 例外シナリオ")
    void close_重複_409() throws Exception {
        willThrow(new BusinessException(ShiftBudgetErrorCode.MONTHLY_ALREADY_CLOSED))
                .given(closeService).close(any(), any());

        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "organization_id", ORG_ID,
                "year_month", "2026-06"));

        mockMvc.perform(post("/api/v1/shift-budget/monthly-close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("organization_id が NULL → 400 (Bean Validation)")
    void close_organization_id必須_400() throws Exception {
        String body = "{\"year_month\":\"2026-06\"}";

        mockMvc.perform(post("/api/v1/shift-budget/monthly-close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
