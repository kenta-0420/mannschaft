package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.EntitlementCheckResponse;
import com.mannschaft.app.billing.api.dto.FeatureItem;
import com.mannschaft.app.billing.api.dto.PlanCatalogResponse;
import com.mannschaft.app.billing.api.dto.PlanItem;
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
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F20.1: {@link BillingPlanController}（カタログ・単一判定）契約テスト（試練）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingPlanController 契約テスト")
class BillingPlanControllerTest {

    @Mock
    private BillingCatalogQueryService catalogQueryService;
    @Mock
    private BillingEntitlementQueryService entitlementQueryService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        BillingPlanController controller = new BillingPlanController(catalogQueryService, entitlementQueryService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(9L);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    @DisplayName("AC: カタログ 200・camelCase")
    void plans_200() throws Exception {
        PlanCatalogResponse catalog = PlanCatalogResponse.builder()
                .plans(List.of(PlanItem.builder()
                        .planKey("FULL")
                        .displayNameKey("billing.plans.full.name")
                        .descriptionKey("billing.plans.full.description")
                        .baseMonthlyPriceJpy(2000)
                        .features(List.of(FeatureItem.builder()
                                .featureKey("ads.hide").category("REVENUE").addonAvailable(true)
                                .addonPriceJpy(300).displayNameKey("k").descriptionKey("d").sortOrder(10)
                                .build()))
                        .priceBands(List.of())
                        .build()))
                .build();
        given(catalogQueryService.getCatalog()).willReturn(catalog);

        mockMvc.perform(get("/api/v1/billing/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plans[0].planKey").value("FULL"))
                .andExpect(jsonPath("$.data.plans[0].baseMonthlyPriceJpy").value(2000))
                .andExpect(jsonPath("$.data.plans[0].features[0].featureKey").value("ads.hide"));
    }

    @Test
    @DisplayName("AC: check 200・entitled/purchasable/plansContaining")
    void check_200() throws Exception {
        given(entitlementQueryService.check(eq(9L), eq(EntitlementScopeKind.TEAM), eq(123L), eq("ads.hide")))
                .willReturn(EntitlementCheckResponse.builder()
                        .entitled(false).featureKey("ads.hide").purchasable(true)
                        .addonPriceJpy(300).plansContaining(List.of("FULL")).build());

        mockMvc.perform(get("/api/v1/billing/entitlements/check")
                        .param("scopeKind", "TEAM").param("scopeId", "123").param("featureKey", "ads.hide"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entitled").value(false))
                .andExpect(jsonPath("$.data.purchasable").value(true))
                .andExpect(jsonPath("$.data.plansContaining[0]").value("FULL"));
    }

    @Test
    @DisplayName("AC-10: check で他スコープ探索は 403（SCOPE_FORBIDDEN）")
    void check_scopeForbidden_403() throws Exception {
        willThrow(new BusinessException(EntitlementErrorCode.SCOPE_FORBIDDEN))
                .given(entitlementQueryService).check(any(), any(), any(), any());
        mockMvc.perform(get("/api/v1/billing/entitlements/check")
                        .param("scopeKind", "TEAM").param("scopeId", "999").param("featureKey", "ads.hide"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_005"));
    }

    @Test
    @DisplayName("AC: check で不正 scopeKind は 400（ENTITLEMENT_009）")
    void check_invalidScopeKind_400() throws Exception {
        mockMvc.perform(get("/api/v1/billing/entitlements/check")
                        .param("scopeKind", "BAD").param("scopeId", "1").param("featureKey", "ads.hide"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_009"));
    }
}
