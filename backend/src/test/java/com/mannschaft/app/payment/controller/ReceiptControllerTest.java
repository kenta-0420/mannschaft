package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.PaymentErrorCode;
import com.mannschaft.app.payment.dto.ReceiptResponse;
import com.mannschaft.app.payment.service.ReceiptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ReceiptController 契約テスト（F08.9 P8 T-RC-01〜02）。
 *
 * <h3>テスト観点</h3>
 * <ul>
 *   <li>T-RC-01: 認証済み払い手が GET /api/v1/member-payments/{id}/receipt → 200 + ReceiptResponse</li>
 *   <li>T-RC-02: 存在しない ID → 404（MEMBER_PAYMENT_NOT_FOUND → GlobalExceptionHandler 変換）</li>
 * </ul>
 *
 * <h3>@WebMvcTest 非互換の回避</h3>
 * {@code MockMvcBuilders.standaloneSetup} + {@code @ExtendWith(MockitoExtension.class)} で
 * Controller・AdviceLayer のみを構成し、Spring Security コンテキストを完全回避する。
 * {@code SecurityUtils.getCurrentUserId()} は {@code MockedStatic} で差し替える。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReceiptController 契約テスト（T-RC-01〜02）")
class ReceiptControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long PAYMENT_ID = 100L;

    @Mock
    private ReceiptService receiptService;

    private MockMvc mockMvc;

    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        StaticMessageSource ms = new StaticMessageSource();
        ReceiptController controller = new ReceiptController(receiptService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();

        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    // -------------------------------------------------------------------------
    // T-RC-01: 正常系 → 200 + ReceiptResponse
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("T-RC-01: 認証済み払い手がアクセス → 200 + ReceiptResponse が返る")
    void getReceipt_authenticated_returns200() throws Exception {
        ReceiptResponse response = new ReceiptResponse(
                PAYMENT_ID,
                null,
                new BigDecimal("5000.00"),
                "JPY",
                LocalDate.of(2026, 6, 10),
                "https://pay.stripe.com/receipts/test",
                null
        );

        given(receiptService.getReceipt(eq(PAYMENT_ID), eq(USER_ID))).willReturn(response);

        mockMvc.perform(get("/api/v1/member-payments/{id}/receipt", PAYMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberPaymentId").value(PAYMENT_ID))
                .andExpect(jsonPath("$.data.amount").value(5000.00))
                .andExpect(jsonPath("$.data.currency").value("JPY"))
                .andExpect(jsonPath("$.data.receiptUrl").value("https://pay.stripe.com/receipts/test"))
                .andExpect(jsonPath("$.data.taxInfo").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // T-RC-02: 存在しない ID → GlobalExceptionHandler 経由で適切なエラーレスポンス
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("T-RC-02: 存在しない ID → BusinessException(MEMBER_PAYMENT_NOT_FOUND) をスローし GlobalExceptionHandler が処理")
    void getReceipt_notFound_throwsAndHandled() throws Exception {
        given(receiptService.getReceipt(eq(PAYMENT_ID), eq(USER_ID)))
                .willThrow(new BusinessException(PaymentErrorCode.MEMBER_PAYMENT_NOT_FOUND));

        mockMvc.perform(get("/api/v1/member-payments/{id}/receipt", PAYMENT_ID))
                .andExpect(status().is4xxClientError());
    }
}
