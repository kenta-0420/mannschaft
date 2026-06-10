package com.mannschaft.app.payment.escrow.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.EscrowStatus;
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

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F22.1 謝礼決済 第二陣: {@link EscrowPaymentController} 契約テスト（T10 PCI 禁則 / 契約 / camelCase）。
 *
 * <p>StandaloneSetup + Mockito で Service をモックし、HTTP 入出力（camelCase）・clientSecret の出し分け・
 * 404 秘匿を検証する。{@code SecurityUtils.getCurrentUserId()} は {@code MockedStatic} で差し替える。
 * #1232 前科に倣い、モック stub の引数個数を Controller の実呼び出しに一致させる。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EscrowPaymentController 契約テスト（第二陣・T10）")
class EscrowPaymentControllerTest {

    @Mock private ConnectChargeService connectChargeService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockedStatic<SecurityUtils> securityUtilsMock;

    private static final Long PAYER_USER_ID = 999L;
    private static final UUID ESCROW_ID = UUID.fromString("019607a0-0000-7000-8000-000000000099");

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        EscrowPaymentController controller = new EscrowPaymentController(connectChargeService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();

        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(PAYER_USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    @DisplayName("GET 決済確認: 札主本人×PENDING_CONFIRMATION→200・clientSecret＋手数料内訳（camelCase）")
    void recruitmentPaymentIntent_payer_returnsClientSecret() throws Exception {
        // 引数個数を Controller の呼び出し（sourceKind, listingId, participantId, actorUserId の 4 個）に一致させる。
        given(connectChargeService.getRecruitmentPaymentView(
                eq(EscrowSourceKind.RECRUITMENT), eq(100L), eq(200L), eq(PAYER_USER_ID)))
                .willReturn(new ConnectChargeService.PaymentView(
                        ESCROW_ID, EscrowStatus.PENDING_CONFIRMATION, "pi_abc_secret",
                        10_000L, 10_250L, 500L));

        mockMvc.perform(get("/api/v1/payment/escrow/recruitment/100/200/payment-intent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clientSecret").value("pi_abc_secret"))
                .andExpect(jsonPath("$.data.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.data.escrowTransactionId").value(ESCROW_ID.toString()))
                .andExpect(jsonPath("$.data.faceAmount").value(10_000))
                .andExpect(jsonPath("$.data.chargeAmount").value(10_250))
                .andExpect(jsonPath("$.data.applicationFeeAmount").value(500))
                // PCI 禁則: pi_xxx / acct_xxx は本文に載せない（clientSecret 値の pi_ プレフィックスは別物=client_secret 自体）。
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("\"acct_"))));
    }

    @Test
    @DisplayName("GET 照会: 受取側 ADMIN→200・clientSecret は null（出し分け・camelCase）")
    void getEscrow_payeeAdmin_clientSecretNull() throws Exception {
        given(connectChargeService.getEscrowView(eq(ESCROW_ID), eq(PAYER_USER_ID)))
                .willReturn(new ConnectChargeService.PaymentView(
                        ESCROW_ID, EscrowStatus.PENDING_CONFIRMATION, null,
                        10_000L, 10_250L, 500L));

        mockMvc.perform(get("/api/v1/payment/escrow/" + ESCROW_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clientSecret").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.data.faceAmount").value(10_000));
    }

    @Test
    @DisplayName("GET 照会: 無関係者→404 秘匿（PAYMENT_C002）")
    void getEscrow_unrelated_notFound() throws Exception {
        given(connectChargeService.getEscrowView(any(), any()))
                .willThrow(new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/v1/payment/escrow/" + ESCROW_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET 決済確認: escrow 未存在（リスナ未起票の競合）→404（準備中）")
    void recruitmentPaymentIntent_notReady_notFound() throws Exception {
        given(connectChargeService.getRecruitmentPaymentView(any(), any(), any(), any()))
                .willThrow(new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/v1/payment/escrow/recruitment/100/200/payment-intent"))
                .andExpect(status().isNotFound());
    }
}
