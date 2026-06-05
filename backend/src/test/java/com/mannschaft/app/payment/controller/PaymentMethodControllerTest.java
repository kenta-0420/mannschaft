package com.mannschaft.app.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.service.PaymentMethodService;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PaymentMethodController 契約テスト（F08.9 P5 第二波）。
 *
 * <p>standaloneSetup + MockitoExtension で Spring Security を回避（#1266 前科・P1 Wave5 流儀）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentMethodController 契約テスト")
class PaymentMethodControllerTest {

    private static final Long USER_ID = 1L;

    @Mock
    private PaymentMethodService paymentMethodService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        PaymentMethodController controller = new PaymentMethodController(paymentMethodService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    @DisplayName("setup-intent → 201・clientSecret/setupIntentId を camelCase で返す")
    void setupIntent_201() throws Exception {
        given(paymentMethodService.createSetupIntent(eq(USER_ID)))
                .willReturn(new StripePaymentProvider.SetupIntentInfo("seti_1", "seti_secret", "requires_payment_method"));

        mockMvc.perform(post("/api/v1/me/payment-methods/setup-intent"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.setupIntentId").value("seti_1"))
                .andExpect(jsonPath("$.data.clientSecret").value("seti_secret"))
                .andExpect(jsonPath("$.data.status").value("requires_payment_method"));
    }

    @Test
    @DisplayName("confirm → 200・defaultPaymentMethod/saved を返す")
    void confirm_200() throws Exception {
        StripeCustomerEntity customer = StripeCustomerEntity.builder()
                .userId(USER_ID).stripeCustomerId("cus_x").defaultPaymentMethod("pm_123").build();
        given(paymentMethodService.confirmPaymentMethod(eq(USER_ID), eq("pm_123"))).willReturn(customer);

        mockMvc.perform(post("/api/v1/me/payment-methods/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"pm_123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultPaymentMethod").value("pm_123"))
                .andExpect(jsonPath("$.data.saved").value(true));
    }

    @Test
    @DisplayName("confirm paymentMethodId 未指定 → 400")
    void confirm_blank_400() throws Exception {
        mockMvc.perform(post("/api/v1/me/payment-methods/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
