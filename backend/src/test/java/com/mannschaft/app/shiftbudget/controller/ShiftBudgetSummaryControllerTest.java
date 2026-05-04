package com.mannschaft.app.shiftbudget.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.dto.AlertResponse;
import com.mannschaft.app.shiftbudget.dto.ConsumptionSummaryResponse;
import com.mannschaft.app.shiftbudget.dto.UserConsumptionDto;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetSummaryService;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ShiftBudgetSummaryController} の MockMvc 結合テスト
 * （Phase 9-δ 第3段で新設、Q7 御裁可）。
 *
 * <p>カバレッジ:</p>
 * <ul>
 *   <li>BUDGET_ADMIN 保有時: {@code by_user} 配列含む全フィールド出力</li>
 *   <li>BUDGET_VIEW のみ: {@code by_user} フィールドそのものが除外（{@code @JsonView} で）+ flags=BY_USER_HIDDEN</li>
 *   <li>SystemAdmin: BUDGET_ADMIN 同等</li>
 *   <li>権限なし → 403</li>
 *   <li>別組織 → 404</li>
 * </ul>
 *
 * <p>{@code MappingJacksonValue} と {@code @JsonView} の組合せにより
 * {@code by_user} はフィールド単位でシリアライズ除外される（DTO 側の値が空でも JSON に出ない）。</p>
 */
@WebMvcTest(ShiftBudgetSummaryController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ShiftBudgetSummaryController 結合テスト")
class ShiftBudgetSummaryControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 1L;
    private static final Long ALLOCATION_ID = 42L;

    @Autowired
    private MockMvc mockMvc;

    @SuppressWarnings("unused")
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ShiftBudgetSummaryService summaryService;

    @MockitoBean
    private AccessControlService accessControlService;

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

    private ConsumptionSummaryResponse responseAsAdmin() {
        return ConsumptionSummaryResponse.builder()
                .allocationId(ALLOCATION_ID)
                .allocatedAmount(new BigDecimal("300000"))
                .consumedAmount(new BigDecimal("245000"))
                .confirmedAmount(new BigDecimal("200000"))
                .plannedAmount(new BigDecimal("45000"))
                .remainingAmount(new BigDecimal("55000"))
                .consumptionRate(new BigDecimal("0.8167"))
                .status("WARN")
                .flags(Collections.emptyList())
                .alerts(List.of(AlertResponse.builder()
                        .id(99L).allocationId(ALLOCATION_ID).thresholdPercent(80)
                        .triggeredAt(LocalDateTime.of(2026, 6, 25, 14, 30))
                        .consumedAmountAtTrigger(new BigDecimal("245000"))
                        .build()))
                .byUser(List.of(
                        UserConsumptionDto.builder().userId(5L)
                                .amount(new BigDecimal("80000"))
                                .hours(new BigDecimal("66.67")).build(),
                        UserConsumptionDto.builder().userId(6L)
                                .amount(new BigDecimal("60000"))
                                .hours(new BigDecimal("50.00")).build()))
                .build();
    }

    private ConsumptionSummaryResponse responseAsViewerOnly() {
        return ConsumptionSummaryResponse.builder()
                .allocationId(ALLOCATION_ID)
                .allocatedAmount(new BigDecimal("300000"))
                .consumedAmount(new BigDecimal("245000"))
                .confirmedAmount(new BigDecimal("200000"))
                .plannedAmount(new BigDecimal("45000"))
                .remainingAmount(new BigDecimal("55000"))
                .consumptionRate(new BigDecimal("0.8167"))
                .status("WARN")
                .flags(List.of("BY_USER_HIDDEN"))
                .alerts(Collections.emptyList())
                .byUser(Collections.emptyList())
                .build();
    }

    /** BUDGET_ADMIN 保有を AccessControlService に答えさせる。 */
    private void givenAdmin() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        // BUDGET_ADMIN 判定で例外を投げない
    }

    /** BUDGET_VIEW のみで BUDGET_ADMIN は持たない設定。 */
    private void givenViewerOnly() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        willThrow(new BusinessException(ShiftBudgetErrorCode.BUDGET_ADMIN_REQUIRED))
                .given(accessControlService)
                .checkPermission(USER_ID, ORG_ID, "ORGANIZATION", "BUDGET_ADMIN");
    }

    @Test
    @DisplayName("BUDGET_ADMIN: 200 + by_user / alerts / 全金額フィールド出力")
    void admin_200() throws Exception {
        givenAdmin();
        given(summaryService.getConsumptionSummary(ORG_ID, ALLOCATION_ID)).willReturn(responseAsAdmin());

        mockMvc.perform(get("/api/v1/shift-budget/allocations/{id}/consumption-summary", ALLOCATION_ID)
                        .header("X-Organization-Id", ORG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allocation_id").value(ALLOCATION_ID))
                .andExpect(jsonPath("$.data.status").value("WARN"))
                .andExpect(jsonPath("$.data.flags").isArray())
                .andExpect(jsonPath("$.data.flags").isEmpty())
                .andExpect(jsonPath("$.data.allocated_amount").value(300000))
                .andExpect(jsonPath("$.data.consumption_rate").value(0.8167))
                .andExpect(jsonPath("$.data.alerts[0].threshold_percent").value(80))
                .andExpect(jsonPath("$.data.by_user").isArray())
                .andExpect(jsonPath("$.data.by_user[0].user_id").value(5))
                .andExpect(jsonPath("$.data.by_user[0].amount").value(80000));
    }

    @Test
    @DisplayName("BUDGET_VIEW のみ: 200 + by_user フィールドが JSON に出ない (@JsonView 除外) + flags=BY_USER_HIDDEN")
    void viewerOnly_byUser除外() throws Exception {
        givenViewerOnly();
        given(summaryService.getConsumptionSummary(ORG_ID, ALLOCATION_ID))
                .willReturn(responseAsViewerOnly());

        mockMvc.perform(get("/api/v1/shift-budget/allocations/{id}/consumption-summary", ALLOCATION_ID)
                        .header("X-Organization-Id", ORG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allocation_id").value(ALLOCATION_ID))
                .andExpect(jsonPath("$.data.status").value("WARN"))
                .andExpect(jsonPath("$.data.flags[0]").value("BY_USER_HIDDEN"))
                .andExpect(jsonPath("$.data.allocated_amount").value(300000))
                // by_user フィールドそのものが @JsonView(BudgetAdmin) 制御で JSON に出ない
                .andExpect(jsonPath("$.data.by_user").doesNotExist());
    }

    @Test
    @DisplayName("SystemAdmin: 200 + by_user 全公開")
    void systemAdmin_byUser公開() throws Exception {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
        given(summaryService.getConsumptionSummary(ORG_ID, ALLOCATION_ID)).willReturn(responseAsAdmin());

        mockMvc.perform(get("/api/v1/shift-budget/allocations/{id}/consumption-summary", ALLOCATION_ID)
                        .header("X-Organization-Id", ORG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.by_user").isArray())
                .andExpect(jsonPath("$.data.by_user[1].user_id").value(6));
    }

    @Test
    @DisplayName("BUDGET_VIEW なし → 403 (Service が BUDGET_VIEW_REQUIRED)")
    void viewなし_403() throws Exception {
        givenViewerOnly();
        willThrow(new BusinessException(ShiftBudgetErrorCode.BUDGET_VIEW_REQUIRED))
                .given(summaryService).getConsumptionSummary(eq(ORG_ID), anyLong());

        mockMvc.perform(get("/api/v1/shift-budget/allocations/{id}/consumption-summary", ALLOCATION_ID)
                        .header("X-Organization-Id", ORG_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("別組織 → 404 (Service が ALLOCATION_NOT_FOUND)")
    void 別組織_404() throws Exception {
        givenViewerOnly();
        willThrow(new BusinessException(ShiftBudgetErrorCode.ALLOCATION_NOT_FOUND))
                .given(summaryService).getConsumptionSummary(ORG_ID, ALLOCATION_ID);

        mockMvc.perform(get("/api/v1/shift-budget/allocations/{id}/consumption-summary", ALLOCATION_ID)
                        .header("X-Organization-Id", ORG_ID))
                .andExpect(status().isNotFound());
    }
}
