package com.mannschaft.app.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.payment.connect.ConnectWebhookService;
import com.mannschaft.app.payment.service.StripeWebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Connect Webhook 受信エンドポイントの契約テスト（T2 署名失敗→400）。
 *
 * <p>{@code /api/v1/webhooks/stripe/connect} は署名失敗時に {@code PAYMENT_C040}（400）を返し、
 * 正当な署名なら 200 を返す。既存 platform {@code /stripe} ハンドラを壊さないことも併せて確認する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StripeWebhookController Connect 契約テスト（T2）")
class StripeConnectWebhookControllerTest {

    @Mock private StripeWebhookService stripeWebhookService;
    @Mock private ConnectWebhookService connectWebhookService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        MessageSource ms = new StaticMessageSource();
        StripeWebhookController controller =
                new StripeWebhookController(stripeWebhookService, connectWebhookService);
        // 生ボディは @RequestBody String で受けるため StringHttpMessageConverter が必須
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new StringHttpMessageConverter(),
                        new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();
    }

    @Test
    @DisplayName("T2: Connect Webhook 署名検証失敗 → 400（PAYMENT_C040）")
    void connectWebhook_signatureInvalid400() throws Exception {
        doThrow(new BusinessException(ConnectPaymentErrorCode.WEBHOOK_SIGNATURE_INVALID))
                .when(connectWebhookService).handleWebhook(any(), any());

        mockMvc.perform(post("/api/v1/webhooks/stripe/connect")
                        .header("Stripe-Signature", "bad")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("payload"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("正常系: Connect Webhook 正当署名 → 200")
    void connectWebhook_ok200() throws Exception {
        doNothing().when(connectWebhookService).handleWebhook(any(), any());

        mockMvc.perform(post("/api/v1/webhooks/stripe/connect")
                        .header("Stripe-Signature", "good")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("payload"))
                .andExpect(status().isOk());

        verify(connectWebhookService).handleWebhook(any(), any());
    }

    @Test
    @DisplayName("既存 platform /stripe ハンドラを壊さない（例外でも 200 で再送防止）")
    void platformWebhook_stillSwallows() throws Exception {
        doThrow(new RuntimeException("boom")).when(stripeWebhookService).handleWebhook(any(), any());

        mockMvc.perform(post("/api/v1/webhooks/stripe")
                        .header("Stripe-Signature", "sig")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("payload"))
                .andExpect(status().isOk());
    }

    // ---- Signature ヘッダなし → 400 ----

    @Test
    @DisplayName("T3: platform /stripe — Stripe-Signature ヘッダなし → 400（PAYMENT_C040）")
    void platformWebhook_missingSignatureHeader_returns400() throws Exception {
        // Stripe-Signature ヘッダを送らない（required=false で受け取り、メソッド冒頭でチェック）
        mockMvc.perform(post("/api/v1/webhooks/stripe")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("payload"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("T4: Connect /stripe/connect — Stripe-Signature ヘッダなし → 400（PAYMENT_C040）")
    void connectWebhook_missingSignatureHeader_returns400() throws Exception {
        // Stripe-Signature ヘッダを送らない（required=false で受け取り、メソッド冒頭でチェック）
        mockMvc.perform(post("/api/v1/webhooks/stripe/connect")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("payload"))
                .andExpect(status().isBadRequest());
    }
}
