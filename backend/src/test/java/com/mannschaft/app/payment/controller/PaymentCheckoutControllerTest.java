package com.mannschaft.app.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.payment.dto.ConnectCheckoutResponse;
import com.mannschaft.app.payment.dto.MembershipCheckoutRequest;
import com.mannschaft.app.payment.service.MemberPaymentService;
import com.mannschaft.app.payment.service.PaymentItemService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PaymentCheckoutController 契約テスト（F08.9 P1 Wave5 T-CC-01〜04）。
 *
 * <h3>テスト観点</h3>
 * <ul>
 *   <li>T-CC-01: 正常系（SELF 払い）→ 201 Created / clientSecret・memberPaymentId が camelCase で返る</li>
 *   <li>T-CC-02: 無権原（払い手 ≠ 受益者 かつ 権原なし）→ 403（MEMBERSHIP_BILLING_001）</li>
 *   <li>T-CC-03: 重複払い（既に PAID な記録あり）→ 409（MEMBERSHIP_BILLING_002）</li>
 *   <li>T-CC-04: 受領口座が READY でない → 409（PAYMENT_C030）</li>
 * </ul>
 *
 * <h3>@WebMvcTest 非互換の回避</h3>
 * {@code @WebMvcTest + @EnableMethodSecurity} は SecurityConfig の完全ロードを要求し
 * テスト環境では多くの Bean 依存で失敗する（#1266 前科）。
 * 本テストは {@code MockMvcBuilders.standaloneSetup} + {@code @ExtendWith(MockitoExtension.class)} で
 * Controller・AdviceLayer のみを構成し、Spring Security コンテキストを完全回避する。
 * {@code SecurityUtils.getCurrentUserId()} は {@code MockedStatic} で差し替える。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentCheckoutController 契約テスト（T-CC-01〜04）")
class PaymentCheckoutControllerTest {

    private static final Long PAYER_USER_ID = 1L;
    private static final Long BENEFICIARY_USER_ID = 1L; // SELF
    private static final Long ITEM_ID = 100L;

    @Mock
    private MemberPaymentService memberPaymentService;

    @Mock
    private PaymentItemService paymentItemService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        PaymentCheckoutController controller = new PaymentCheckoutController(memberPaymentService, paymentItemService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();

        // SecurityUtils.getCurrentUserId() をモックして認証済みユーザーを差し替え
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(PAYER_USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    // -------------------------------------------------------------------------
    // T-CC-01: 正常系（SELF払い）→ 201 Created
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("T-CC-01: 正常系（SELF払い）→ 201 Created・camelCase・memberPaymentId 返却")
    void createConnectCheckout_self_201() throws Exception {
        UUID escrowId = UUID.fromString("019607a0-0000-7000-8000-000000000001");
        ConnectCheckoutResponse serviceResponse =
                new ConnectCheckoutResponse("pi_test_secret_001", 42L, escrowId);

        given(memberPaymentService.createConnectCheckout(
                eq(ITEM_ID), eq(BENEFICIARY_USER_ID), eq(PAYER_USER_ID), anyString()))
                .willReturn(serviceResponse);

        String body = objectMapper.writeValueAsString(
                new MembershipCheckoutRequest(BENEFICIARY_USER_ID, null));

        mockMvc.perform(post("/api/v1/payment-items/{itemId}/checkout", ITEM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.clientSecret").value("pi_test_secret_001"))
                .andExpect(jsonPath("$.data.memberPaymentId").value(42))
                .andExpect(jsonPath("$.data.escrowTransactionId").value(escrowId.toString()));
    }

    @Test
    @DisplayName("T-CC-01b: Idempotency-Key ヘッダが付いていれば Service にそのまま渡す")
    void createConnectCheckout_idempotencyHeader_forwarded() throws Exception {
        String fixedKey = "idem-key-from-header-001";
        UUID escrowId = UUID.fromString("019607a0-0000-7000-8000-000000000002");
        ConnectCheckoutResponse serviceResponse =
                new ConnectCheckoutResponse("pi_test_secret_002", 43L, escrowId);

        given(memberPaymentService.createConnectCheckout(
                eq(ITEM_ID), eq(BENEFICIARY_USER_ID), eq(PAYER_USER_ID), eq(fixedKey)))
                .willReturn(serviceResponse);

        String body = objectMapper.writeValueAsString(
                new MembershipCheckoutRequest(BENEFICIARY_USER_ID, null));

        mockMvc.perform(post("/api/v1/payment-items/{itemId}/checkout", ITEM_ID)
                        .header("Idempotency-Key", fixedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.memberPaymentId").value(43));
    }

    // -------------------------------------------------------------------------
    // T-CC-02: 無権原 → 403（MEMBERSHIP_BILLING_001）
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("T-CC-02: 無権原（払い手 ≠ 受益者・権原なし）→ 403（MEMBERSHIP_BILLING_001）")
    void createConnectCheckout_notAuthorized_403() throws Exception {
        Long otherUserId = 999L;
        given(memberPaymentService.createConnectCheckout(
                eq(ITEM_ID), eq(otherUserId), eq(PAYER_USER_ID), anyString()))
                .willThrow(new BusinessException(MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED));

        String body = objectMapper.writeValueAsString(
                new MembershipCheckoutRequest(otherUserId, null));

        mockMvc.perform(post("/api/v1/payment-items/{itemId}/checkout", ITEM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_BILLING_001"));
    }

    // -------------------------------------------------------------------------
    // T-CC-03: 重複払い → 409（MEMBERSHIP_BILLING_002）
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("T-CC-03: 重複払い（既に PAID 記録あり）→ 409（MEMBERSHIP_BILLING_002）")
    void createConnectCheckout_alreadyPaid_409() throws Exception {
        given(memberPaymentService.createConnectCheckout(
                eq(ITEM_ID), eq(BENEFICIARY_USER_ID), eq(PAYER_USER_ID), anyString()))
                .willThrow(new BusinessException(MembershipBillingErrorCode.MEMBERSHIP_ALREADY_PAID));

        String body = objectMapper.writeValueAsString(
                new MembershipCheckoutRequest(BENEFICIARY_USER_ID, null));

        mockMvc.perform(post("/api/v1/payment-items/{itemId}/checkout", ITEM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_BILLING_002"));
    }

    // -------------------------------------------------------------------------
    // T-CC-04: 受領口座が READY でない → 409（PAYMENT_C030）
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("T-CC-04: 受領口座 READY でない → 409（PAYMENT_C030）")
    void createConnectCheckout_connectAccountNotReady_409() throws Exception {
        given(memberPaymentService.createConnectCheckout(
                eq(ITEM_ID), eq(BENEFICIARY_USER_ID), eq(PAYER_USER_ID), anyString()))
                .willThrow(new BusinessException(ConnectPaymentErrorCode.ONBOARDING_NOT_READY));

        String body = objectMapper.writeValueAsString(
                new MembershipCheckoutRequest(BENEFICIARY_USER_ID, null));

        mockMvc.perform(post("/api/v1/payment-items/{itemId}/checkout", ITEM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_C030"));
    }

    // -------------------------------------------------------------------------
    // バリデーション: beneficiaryUserId 未指定 → 400
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("beneficiaryUserId が null → 400 Bad Request（バリデーションエラー）")
    void createConnectCheckout_nullBeneficiary_400() throws Exception {
        String body = "{\"idempotencyKey\":\"some-key\"}"; // beneficiaryUserId を省略

        mockMvc.perform(post("/api/v1/payment-items/{itemId}/checkout", ITEM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
