package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.PublicBillingCatalogResponse;
import com.mannschaft.app.billing.api.dto.PublicMoney;
import com.mannschaft.app.billing.api.dto.PublicPlan;
import com.mannschaft.app.common.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@link PublicBillingCatalogController} のHTTP契約テスト。認可境界はSecurityConfigのテストで確認する。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PublicBillingCatalogController 契約テスト")
class PublicBillingCatalogControllerTest {

    @Mock
    private BillingPublicCatalogQueryService catalogQueryService;

    @Test
    @DisplayName("公開価格: 必須scopeKindをSERVICEへ正規化して200で返す")
    void plans_returnsCatalogForScopeKind() throws Exception {
        PublicBillingCatalogResponse response = PublicBillingCatalogResponse.builder()
                .scopeKind("USER")
                .plans(List.of(PublicPlan.builder()
                        .planKey("STANDARD")
                        .displayNameKey("billing.plans.standard.name")
                        .descriptionKey("billing.plans.standard.description")
                        .startingMonthlyTotal(PublicMoney.builder()
                                .currency("JPY")
                                .amountIncludingTax(1100)
                                .amountExcludingTax(1000)
                                .taxAmount(100)
                                .taxName("消費税")
                                .taxRateBasisPoints(1000)
                                .build())
                        .priceBands(List.of())
                        .quoteRequired(false)
                        .available(true)
                        .featureKeys(List.of("schedule.advanced"))
                        .build()))
                .addons(List.of())
                .build();
        given(catalogQueryService.getPublicCatalog(eq(EntitlementScopeKind.USER))).willReturn(response);

        mockMvc().perform(get("/api/v1/public/billing/plans").param("scopeKind", " user "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scopeKind").value("USER"))
                .andExpect(jsonPath("$.data.plans[0].startingMonthlyTotal.currency").value("JPY"))
                .andExpect(jsonPath("$.data.plans[0].startingMonthlyTotal.amountIncludingTax").value(1100))
                .andExpect(jsonPath("$.data.plans[0].featureKeys[0]").value("schedule.advanced"));
    }

    @Test
    @DisplayName("公開価格: scopeKind未指定は400")
    void plans_requiresScopeKind() throws Exception {
        mockMvc().perform(get("/api/v1/public/billing/plans"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("公開価格: 対象外scopeKindはENTITLEMENT_009で400")
    void plans_rejectsUnknownScopeKind() throws Exception {
        mockMvc().perform(get("/api/v1/public/billing/plans").param("scopeKind", "SYSTEM"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_009"));
    }

    private MockMvc mockMvc() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        return MockMvcBuilders.standaloneSetup(new PublicBillingCatalogController(catalogQueryService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
    }
}
