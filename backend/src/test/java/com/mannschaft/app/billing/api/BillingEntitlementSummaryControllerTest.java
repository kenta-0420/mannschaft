package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.ActiveContract;
import com.mannschaft.app.billing.api.dto.EntitledFeature;
import com.mannschaft.app.billing.api.dto.EntitlementSummaryResponse;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F20.1: {@link BillingEntitlementSummaryController}（権利サマリ）契約テスト（試練）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingEntitlementSummaryController 契約テスト")
class BillingEntitlementSummaryControllerTest {

    @Mock
    private BillingEntitlementQueryService queryService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockedStatic<SecurityUtils> securityUtilsMock;

    private EntitlementSummaryResponse summary(String scopeKind, Long scopeId) {
        return EntitlementSummaryResponse.builder()
                .scopeKind(scopeKind)
                .scopeId(scopeId)
                .activePlan(ActiveContract.builder()
                        .contractId(UUID.randomUUID().toString()).planKey("FULL")
                        .contractedAt(LocalDateTime.now()).priceJpySnapshot(null).build())
                .activeAddons(List.of())
                .entitledFeatures(List.of(EntitledFeature.builder()
                        .featureKey("ads.hide").sourceKind("PLAN").validUntil(null).build()))
                .build();
    }

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        BillingEntitlementSummaryController controller = new BillingEntitlementSummaryController(queryService);
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
    @DisplayName("AC: /me 200・USER スコープ（本人固定）")
    void me_200() throws Exception {
        given(queryService.getSummary(eq(EntitlementScopeKind.USER), eq(9L)))
                .willReturn(summary("USER", 9L));
        mockMvc.perform(get("/api/v1/me/entitlements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scopeKind").value("USER"))
                .andExpect(jsonPath("$.data.scopeId").value(9))
                .andExpect(jsonPath("$.data.activePlan.planKey").value("FULL"))
                .andExpect(jsonPath("$.data.entitledFeatures[0].sourceKind").value("PLAN"));
    }

    @Test
    @DisplayName("AC: /teams 200・TEAM スコープ")
    void team_200() throws Exception {
        given(queryService.getSummary(eq(EntitlementScopeKind.TEAM), eq(123L)))
                .willReturn(summary("TEAM", 123L));
        mockMvc.perform(get("/api/v1/teams/{teamId}/entitlements", 123L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scopeKind").value("TEAM"))
                .andExpect(jsonPath("$.data.scopeId").value(123));
    }

    @Test
    @DisplayName("AC: /organizations 200・ORG スコープ")
    void org_200() throws Exception {
        given(queryService.getSummary(eq(EntitlementScopeKind.ORG), eq(55L)))
                .willReturn(summary("ORG", 55L));
        mockMvc.perform(get("/api/v1/organizations/{orgId}/entitlements", 55L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scopeKind").value("ORG"))
                .andExpect(jsonPath("$.data.scopeId").value(55));
    }
}
