package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.api.dto.ContractResponse;
import com.mannschaft.app.billing.api.dto.PagedContractResponse;
import com.mannschaft.app.billing.api.dto.PlanAdminResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * F20.1: {@link SystemAdminBillingController}（シスアド運用）契約テスト（試練）。
 *
 * <p>認可（AC-17: 非 SYSTEM_ADMIN→403）は SecurityConfig の {@code /api/v1/system-admin/**}
 * ＝ {@code hasRole('SYSTEM_ADMIN')} ＋ メソッド {@code @PreAuthorize} で担保し、注釈存在は
 * {@code BillingAuthorizationAnnotationTest} が照合する。本テストはステータス/契約/バリデーションを検証。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SystemAdminBillingController 契約テスト")
class SystemAdminBillingControllerTest {

    @Mock
    private SystemAdminBillingService service;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockedStatic<SecurityUtils> securityUtilsMock;

    private static final long ADMIN_ID = 1L;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        SystemAdminBillingController controller = new SystemAdminBillingController(service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(ADMIN_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    private PlanAdminResponse plan(String key, boolean enabled) {
        return PlanAdminResponse.builder()
                .planKey(key).displayNameKey("n").descriptionKey("d")
                .baseMonthlyPriceJpy(2000).sortOrder(10).enabled(enabled).build();
    }

    private String planBody() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "displayNameKey", "billing.plans.full.name",
                "descriptionKey", "billing.plans.full.description",
                "baseMonthlyPriceJpy", 2000, "sortOrder", 10, "enabled", true));
    }

    // ---- プラン CRUD ----

    @Test
    @DisplayName("AC: プラン一覧 200")
    void listPlans_200() throws Exception {
        given(service.listPlans()).willReturn(List.of(plan("FREE", true), plan("FULL", false)));
        mockMvc.perform(get("/api/v1/system-admin/billing/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].planKey").value("FREE"))
                .andExpect(jsonPath("$.data[1].enabled").value(false));
    }

    @Test
    @DisplayName("AC: プラン詳細 不在は 404（ENTITLEMENT_001）")
    void getPlan_404() throws Exception {
        given(service.getPlan("NOPE")).willThrow(new BusinessException(EntitlementErrorCode.PLAN_NOT_FOUND));
        mockMvc.perform(get("/api/v1/system-admin/billing/plans/{k}", "NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_001"));
    }

    @Test
    @DisplayName("AC: プラン新規 201")
    void createPlan_201() throws Exception {
        given(service.createPlan(eq("FULL"), any())).willReturn(plan("FULL", true));
        mockMvc.perform(post("/api/v1/system-admin/billing/plans/{k}", "FULL")
                        .contentType(MediaType.APPLICATION_JSON).content(planBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.planKey").value("FULL"));
    }

    @Test
    @DisplayName("AC: プラン新規 既存キーは 400（ENTITLEMENT_010）")
    void createPlan_duplicate_400() throws Exception {
        willThrow(new BusinessException(EntitlementErrorCode.PLAN_MASTER_VALIDATION_FAILED))
                .given(service).createPlan(eq("FREE"), any());
        mockMvc.perform(post("/api/v1/system-admin/billing/plans/{k}", "FREE")
                        .contentType(MediaType.APPLICATION_JSON).content(planBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_010"));
    }

    @Test
    @DisplayName("AC: プラン削除 参照中は 409（ENTITLEMENT_012）")
    void deletePlan_inUse_409() throws Exception {
        willThrow(new BusinessException(EntitlementErrorCode.PLAN_MASTER_IN_USE))
                .given(service).deletePlan("FULL");
        mockMvc.perform(delete("/api/v1/system-admin/billing/plans/{k}", "FULL"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_012"));
    }

    @Test
    @DisplayName("AC: プラン削除 未参照は 204")
    void deletePlan_204() throws Exception {
        mockMvc.perform(delete("/api/v1/system-admin/billing/plans/{k}", "BASIC"))
                .andExpect(status().isNoContent());
    }

    // ---- 機能カタログ・バリデーション ----

    @Test
    @DisplayName("AC: 機能新規 REVENUE×非営利無料は 400（ENTITLEMENT_010）")
    void createFeature_revenueNonprofit_400() throws Exception {
        willThrow(new BusinessException(EntitlementErrorCode.PLAN_MASTER_VALIDATION_FAILED))
                .given(service).createFeature(eq("ads.hide"), any());
        String body = objectMapper.writeValueAsString(Map.of(
                "category", "REVENUE", "addonAvailable", true, "freeForNonprofit", true,
                "displayNameKey", "n", "descriptionKey", "d", "sortOrder", 10, "enabled", true));
        mockMvc.perform(post("/api/v1/system-admin/billing/features/{k}", "ads.hide")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_010"));
    }

    @Test
    @DisplayName("AC: plan_features 置換 実在しない機能は 400（ENTITLEMENT_010）")
    void replacePlanFeatures_invalid_400() throws Exception {
        willThrow(new BusinessException(EntitlementErrorCode.PLAN_MASTER_VALIDATION_FAILED))
                .given(service).replacePlanFeatures(eq("FULL"), any());
        String body = objectMapper.writeValueAsString(Map.of("featureKeys", List.of("nope.key")));
        mockMvc.perform(put("/api/v1/system-admin/billing/plans/{k}/features", "FULL")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_010"));
    }

    @Test
    @DisplayName("AC: price-bands 置換 200(204)")
    void replacePriceBands_204() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("bands", List.of(
                Map.of("scopeKind", "TEAM", "bandNo", 1, "minMembers", 1, "maxMembers", 20, "monthlyPriceJpy", 3000))));
        mockMvc.perform(put("/api/v1/system-admin/billing/plans/{k}/price-bands", "FULL")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
    }

    // ---- 手動付与・契約検索 ----

    @Test
    @DisplayName("AC: 手動付与 201")
    void grant_201() throws Exception {
        ContractResponse resp = ContractResponse.builder()
                .contractId(UUID.randomUUID().toString()).scopeKind("TEAM").scopeId(123L)
                .contractKind("PLAN").planKey("FULL").status("ACTIVE")
                .contractedAt(LocalDateTime.now()).grantedFeatureKeys(List.of("ads.hide")).build();
        given(service.grant(any(), eq(ADMIN_ID))).willReturn(resp);
        String body = objectMapper.writeValueAsString(Map.of(
                "scopeKind", "TEAM", "scopeId", 123, "contractKind", "PLAN", "planKey", "FULL"));
        mockMvc.perform(post("/api/v1/system-admin/billing/grants")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.scopeKind").value("TEAM"));
    }

    @Test
    @DisplayName("AC: 契約横断検索 200・ページング")
    void searchContracts_200() throws Exception {
        given(service.searchContracts(eq("TEAM"), eq(123L), eq("ACTIVE"), eq(0), eq(20)))
                .willReturn(PagedContractResponse.builder()
                        .content(List.of()).page(0).size(20).totalElements(0L).build());
        mockMvc.perform(get("/api/v1/system-admin/billing/contracts")
                        .param("scopeKind", "TEAM").param("scopeId", "123").param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }
}
