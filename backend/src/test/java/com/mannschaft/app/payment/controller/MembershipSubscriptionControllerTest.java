package com.mannschaft.app.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.BillingInterval;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.payment.MembershipSubscriptionStatus;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import com.mannschaft.app.payment.service.MembershipSubscriptionService;
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

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MembershipSubscriptionController 契約テスト（F08.9 P5 第二波）。
 *
 * <p>standaloneSetup + MockitoExtension で Spring Security を回避（#1266 前科・P1 Wave5 流儀）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MembershipSubscriptionController 契約テスト")
class MembershipSubscriptionControllerTest {

    private static final Long PAYER = 1L;
    private static final Long BENEFICIARY = 1L;
    private static final Long ITEM_ID = 100L;
    private static final UUID SUB_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000cc");

    @Mock
    private MembershipSubscriptionService service;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        // 本番同様 ISO-8601 文字列で日付を出力（タイムスタンプ配列にしない）。
        objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MessageSource ms = new StaticMessageSource();
        MembershipSubscriptionController controller = new MembershipSubscriptionController(service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(PAYER);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    private MembershipSubscriptionEntity pendingSubscription() {
        MembershipSubscriptionEntity sub = MembershipSubscriptionEntity.builder()
                .paymentItemId(ITEM_ID)
                .beneficiaryUserId(BENEFICIARY).payerUserId(PAYER)
                .scopeKind(ScopeKind.TEAM).scopeId(50L)
                .payeeConnectAccountId(UUID.randomUUID())
                .billingInterval(BillingInterval.MONTHLY)
                .status(MembershipSubscriptionStatus.PENDING)
                .feePolicyKey("DEFAULT").faceAmount(3000).currency("JPY")
                .stripeSubscriptionId("sub_abc").cancelAtPeriodEnd(false)
                .build();
        sub.setId(SUB_ID);
        return sub;
    }

    @Test
    @DisplayName("subscribe 正常系 → 201・camelCase・PENDING")
    void subscribe_201() throws Exception {
        given(service.subscribe(eq(ITEM_ID), eq(PAYER), eq(BENEFICIARY), any(), anyString()))
                .willReturn(pendingSubscription());

        String body = "{\"beneficiaryUserId\":1,\"billingAnchorDay\":15}";

        mockMvc.perform(post("/api/v1/payment-items/{itemId}/subscribe", ITEM_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(SUB_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.faceAmount").value(3000))
                .andExpect(jsonPath("$.data.stripeSubscriptionId").value("sub_abc"));
    }

    @Test
    @DisplayName("subscribe 無権原 → 403（MEMBERSHIP_BILLING_001）")
    void subscribe_notAuthorized_403() throws Exception {
        given(service.subscribe(eq(ITEM_ID), eq(PAYER), eq(2L), any(), anyString()))
                .willThrow(new BusinessException(MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED));

        String body = "{\"beneficiaryUserId\":2}";

        mockMvc.perform(post("/api/v1/payment-items/{itemId}/subscribe", ITEM_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_BILLING_001"));
    }

    @Test
    @DisplayName("subscribe 非継続項目 → 409（MEMBERSHIP_BILLING_019）")
    void subscribe_notRecurring_409() throws Exception {
        given(service.subscribe(eq(ITEM_ID), eq(PAYER), eq(BENEFICIARY), any(), anyString()))
                .willThrow(new BusinessException(MembershipBillingErrorCode.SUBSCRIPTION_ITEM_NOT_RECURRING));

        String body = "{\"beneficiaryUserId\":1}";

        mockMvc.perform(post("/api/v1/payment-items/{itemId}/subscribe", ITEM_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_BILLING_019"));
    }

    @Test
    @DisplayName("subscribe PM 未保存 → 409（MEMBERSHIP_BILLING_020）")
    void subscribe_pmNotSaved_409() throws Exception {
        given(service.subscribe(eq(ITEM_ID), eq(PAYER), eq(BENEFICIARY), any(), anyString()))
                .willThrow(new BusinessException(MembershipBillingErrorCode.SUBSCRIPTION_PAYMENT_METHOD_NOT_SAVED));

        String body = "{\"beneficiaryUserId\":1}";

        mockMvc.perform(post("/api/v1/payment-items/{itemId}/subscribe", ITEM_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_BILLING_020"));
    }

    @Test
    @DisplayName("subscribe 受領口座 非READY → 409（PAYMENT_C030）")
    void subscribe_notReady_409() throws Exception {
        given(service.subscribe(eq(ITEM_ID), eq(PAYER), eq(BENEFICIARY), any(), anyString()))
                .willThrow(new BusinessException(ConnectPaymentErrorCode.ONBOARDING_NOT_READY));

        String body = "{\"beneficiaryUserId\":1}";

        mockMvc.perform(post("/api/v1/payment-items/{itemId}/subscribe", ITEM_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_C030"));
    }

    @Test
    @DisplayName("subscribe beneficiaryUserId 未指定 → 400")
    void subscribe_nullBeneficiary_400() throws Exception {
        mockMvc.perform(post("/api/v1/payment-items/{itemId}/subscribe", ITEM_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("cancel 正常系 → 200・cancelAtPeriodEnd=true・期末日")
    void cancel_200() throws Exception {
        MembershipSubscriptionEntity cancelled = pendingSubscription().toBuilder()
                .status(MembershipSubscriptionStatus.ACTIVE)
                .cancelAtPeriodEnd(true)
                .currentPeriodEnd(java.time.LocalDate.of(2026, 9, 30))
                .build();
        cancelled.setId(SUB_ID);
        given(service.cancel(eq(SUB_ID), eq(PAYER))).willReturn(cancelled);

        mockMvc.perform(delete("/api/v1/membership-subscriptions/{id}", SUB_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cancelAtPeriodEnd").value(true))
                .andExpect(jsonPath("$.data.currentPeriodEnd").value("2026-09-30"));
    }

    @Test
    @DisplayName("cancel 無権原 → 403（MEMBERSHIP_BILLING_018）")
    void cancel_notAuthorized_403() throws Exception {
        given(service.cancel(eq(SUB_ID), eq(PAYER)))
                .willThrow(new BusinessException(MembershipBillingErrorCode.SUBSCRIPTION_NOT_AUTHORIZED));

        mockMvc.perform(delete("/api/v1/membership-subscriptions/{id}", SUB_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_BILLING_018"));
    }

    @Test
    @DisplayName("cancel 不在 → 404（MEMBERSHIP_BILLING_015）")
    void cancel_notFound_404() throws Exception {
        given(service.cancel(eq(SUB_ID), eq(PAYER)))
                .willThrow(new BusinessException(MembershipBillingErrorCode.SUBSCRIPTION_NOT_FOUND));

        mockMvc.perform(delete("/api/v1/membership-subscriptions/{id}", SUB_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_BILLING_015"));
    }
}
