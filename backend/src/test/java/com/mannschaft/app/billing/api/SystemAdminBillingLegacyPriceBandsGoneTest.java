package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 試練隊（第3陣）J群: 旧 API の廃止（AC-140〜AC-142）。
 *
 * <p>正本: `.claude/campaigns/price-rev-plan-v3.md` J群。旧 {@code PUT /plans/{planKey}/price-bands}
 * （{@code plan_price_bands} を delete/saveAll する唯一のシスアド向けエンドポイント）は 410 を返し、
 * 本文で新 API {@code POST /price-revisions} への誘導を含むこと、かつ {@code SystemAdminBillingService}
 * の該当メソッドが一切呼ばれなくなることを固定する。</p>
 *
 * <p>本テストは現行実装（204・{@code service.replacePriceBands} 呼び出し）に対する red である。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("旧 PUT /plans/{planKey}/price-bands の廃止（AC-140〜142）")
class SystemAdminBillingLegacyPriceBandsGoneTest {

    @Mock
    private SystemAdminBillingService service;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockedStatic<SecurityUtils> securityUtilsMock;

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
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    @DisplayName("AC-140: PUT /plans/{planKey}/price-bands は410を返す")
    void legacyPriceBandsReplaceReturns410() throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of("bands", java.util.List.of()));
        mockMvc.perform(put("/api/v1/system-admin/billing/plans/FULL/price-bands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isGone());
    }

    @Test
    @DisplayName("AC-141: 410の本文が新API(POST /price-revisions)への誘導を含む")
    void legacyPriceBandsReplaceBodyGuidesToNewApi() throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of("bands", java.util.List.of()));
        mockMvc.perform(put("/api/v1/system-admin/billing/plans/FULL/price-bands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isGone())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/price-revisions")));
    }

    @Test
    @DisplayName("AC-142: 410化後はSystemAdminBillingService側の旧置換メソッドが一切呼ばれない")
    void legacyPriceBandsReplaceNeverCallsService() throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of("bands", java.util.List.of()));
        mockMvc.perform(put("/api/v1/system-admin/billing/plans/FULL/price-bands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));

        verifyNoInteractions(service);
    }
}
