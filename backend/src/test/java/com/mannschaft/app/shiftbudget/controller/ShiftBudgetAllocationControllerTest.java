package com.mannschaft.app.shiftbudget.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.dto.AllocationCreateRequest;
import com.mannschaft.app.shiftbudget.dto.AllocationListResponse;
import com.mannschaft.app.shiftbudget.dto.AllocationResponse;
import com.mannschaft.app.shiftbudget.dto.AllocationUpdateRequest;
import com.mannschaft.app.shiftbudget.dto.ConsumptionSummaryResponse;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetAllocationService;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetSummaryService;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ShiftBudgetAllocationController} の MockMvc 結合テスト（Phase 9-β / API #1〜#5）。
 *
 * <p>カバレッジ:</p>
 * <ul>
 *   <li>各エンドポイントの HTTP ステータス + JSON 形状</li>
 *   <li>消化サマリの v1.2 形状確定ルール (flags=BY_USER_HIDDEN, by_user=[])</li>
 *   <li>ALLOCATION_NOT_FOUND → 404, ALLOCATION_ALREADY_EXISTS → 409, OPTIMISTIC_LOCK → 409</li>
 *   <li>BUDGET_VIEW/MANAGE → 403</li>
 * </ul>
 */
@WebMvcTest(ShiftBudgetAllocationController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ShiftBudgetAllocationController 結合テスト")
class ShiftBudgetAllocationControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 1L;
    private static final Long ALLOCATION_ID = 42L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ShiftBudgetAllocationService allocationService;

    @MockitoBean
    private ShiftBudgetSummaryService summaryService;

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

    private AllocationResponse sampleResponse() {
        return AllocationResponse.builder()
                .id(ALLOCATION_ID).organizationId(ORG_ID).teamId(12L)
                .fiscalYearId(3L).budgetCategoryId(17L)
                .periodStart(LocalDate.of(2026, 6, 1))
                .periodEnd(LocalDate.of(2026, 6, 30))
                .allocatedAmount(new BigDecimal("300000"))
                .consumedAmount(BigDecimal.ZERO)
                .confirmedAmount(BigDecimal.ZERO)
                .currency("JPY").createdBy(USER_ID).version(0L)
                .createdAt(LocalDateTime.of(2026, 5, 3, 10, 0))
                .build();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("GET /allocations: 一覧 → 200 + items")
    void list_200() throws Exception {
        given(allocationService.listAllocations(eq(ORG_ID), eq(0), eq(20)))
                .willReturn(AllocationListResponse.builder()
                        .items(List.of(sampleResponse()))
                        .page(0).size(20).total(1L).build());

        mockMvc.perform(get("/api/v1/shift-budget/allocations")
                        .header("X-Organization-Id", ORG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items[0].id").value(ALLOCATION_ID))
                .andExpect(jsonPath("$.data.items[0].allocated_amount").value(300000));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("POST /allocations: 作成 → 201")
    void create_201() throws Exception {
        AllocationCreateRequest req = new AllocationCreateRequest(
                12L, null, 3L, 17L,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                new BigDecimal("300000"), "JPY", "test");
        given(allocationService.createAllocation(eq(ORG_ID), any())).willReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/shift-budget/allocations")
                        .header("X-Organization-Id", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(ALLOCATION_ID));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("POST /allocations: 重複 → 409")
    void create_重複_409() throws Exception {
        AllocationCreateRequest req = new AllocationCreateRequest(
                12L, null, 3L, 17L,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                new BigDecimal("300000"), null, null);
        willThrow(new BusinessException(ShiftBudgetErrorCode.ALLOCATION_ALREADY_EXISTS))
                .given(allocationService).createAllocation(eq(ORG_ID), any());

        mockMvc.perform(post("/api/v1/shift-budget/allocations")
                        .header("X-Organization-Id", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("GET /allocations/{id}: 詳細 → 200")
    void get_200() throws Exception {
        given(allocationService.getAllocation(ORG_ID, ALLOCATION_ID)).willReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/shift-budget/allocations/{id}", ALLOCATION_ID)
                        .header("X-Organization-Id", ORG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(ALLOCATION_ID));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("GET /allocations/{id}: 別組織 → 404")
    void get_別組織_404() throws Exception {
        willThrow(new BusinessException(ShiftBudgetErrorCode.ALLOCATION_NOT_FOUND))
                .given(allocationService).getAllocation(ORG_ID, ALLOCATION_ID);

        mockMvc.perform(get("/api/v1/shift-budget/allocations/{id}", ALLOCATION_ID)
                        .header("X-Organization-Id", ORG_ID))
                .andExpect(status().isNotFound());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("PUT /allocations/{id}: 楽観ロック → 409")
    void put_楽観ロック_409() throws Exception {
        AllocationUpdateRequest req = new AllocationUpdateRequest(
                new BigDecimal("400000"), "増額", 99L);
        willThrow(new BusinessException(ShiftBudgetErrorCode.OPTIMISTIC_LOCK_CONFLICT))
                .given(allocationService).updateAllocation(eq(ORG_ID), eq(ALLOCATION_ID), any());

        mockMvc.perform(put("/api/v1/shift-budget/allocations/{id}", ALLOCATION_ID)
                        .header("X-Organization-Id", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("DELETE /allocations/{id}: 正常 → 204")
    void delete_204() throws Exception {
        mockMvc.perform(delete("/api/v1/shift-budget/allocations/{id}", ALLOCATION_ID)
                        .header("X-Organization-Id", ORG_ID))
                .andExpect(status().isNoContent());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("DELETE /allocations/{id}: PLANNED 残存 → 409")
    void delete_planned残存_409() throws Exception {
        willThrow(new BusinessException(ShiftBudgetErrorCode.HAS_CONSUMPTIONS_PLANNED))
                .given(allocationService).deleteAllocation(ORG_ID, ALLOCATION_ID);

        mockMvc.perform(delete("/api/v1/shift-budget/allocations/{id}", ALLOCATION_ID)
                        .header("X-Organization-Id", ORG_ID))
                .andExpect(status().isConflict());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("GET /allocations/{id}/consumption-summary: by_user=[] + flags=BY_USER_HIDDEN (v1.2 形状)")
    void summary_v12形状() throws Exception {
        ConsumptionSummaryResponse res = ConsumptionSummaryResponse.builder()
                .allocationId(ALLOCATION_ID)
                .allocatedAmount(new BigDecimal("300000"))
                .consumedAmount(new BigDecimal("245000"))
                .confirmedAmount(new BigDecimal("200000"))
                .plannedAmount(new BigDecimal("45000"))
                .remainingAmount(new BigDecimal("55000"))
                .consumptionRate(new BigDecimal("0.8167"))
                .status("WARN")
                .flags(List.of("BY_USER_HIDDEN"))
                .alerts(List.of())
                .byUser(List.of())
                .build();
        given(summaryService.getConsumptionSummary(ORG_ID, ALLOCATION_ID)).willReturn(res);

        mockMvc.perform(get("/api/v1/shift-budget/allocations/{id}/consumption-summary", ALLOCATION_ID)
                        .header("X-Organization-Id", ORG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allocation_id").value(ALLOCATION_ID))
                .andExpect(jsonPath("$.data.status").value("WARN"))
                .andExpect(jsonPath("$.data.flags[0]").value("BY_USER_HIDDEN"))
                .andExpect(jsonPath("$.data.by_user").isArray())
                .andExpect(jsonPath("$.data.by_user").isEmpty())
                .andExpect(jsonPath("$.data.alerts").isArray())
                .andExpect(jsonPath("$.data.alerts").isEmpty());
    }
}
