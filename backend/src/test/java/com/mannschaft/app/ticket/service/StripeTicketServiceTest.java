package com.mannschaft.app.ticket.service;

import com.mannschaft.app.ticket.entity.TicketProductEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StripeTicketServicePlaceholder} の単体テスト。
 * Stripe回数券決済プレースホルダー実装の各メソッドの戻り値を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StripeTicketServicePlaceholder 単体テスト")
class StripeTicketServiceTest {

    @InjectMocks
    private StripeTicketServicePlaceholder stripeTicketService;

    // ========================================
    // テスト用ヘルパー
    // ========================================

    private TicketProductEntity createProduct() {
        return TicketProductEntity.builder()
                .teamId(1L)
                .name("10回券")
                .description("体験レッスン10回分")
                .totalTickets(10)
                .price(10000)
                .taxRate(new BigDecimal("10.00"))
                .validityDays(90)
                .isOnlinePurchasable(true)
                .isActive(true)
                .sortOrder(0)
                .createdBy(100L)
                .build();
    }

    // ========================================
    // createStripeProduct
    // ========================================

    @Nested
    @DisplayName("createStripeProduct")
    class CreateStripeProduct {

        @Test
        @DisplayName("正常系: ダミーのProduct IDとPrice IDが返る")
        void createStripeProduct_正常_ダミーIDが返る() {
            // Given
            TicketProductEntity product = createProduct();

            // When
            StripeTicketService.StripeProductResult result = stripeTicketService.createStripeProduct(product);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.productId()).startsWith("prod_placeholder_");
            assertThat(result.priceId()).startsWith("price_placeholder_");
        }
    }

    // ========================================
    // recreateStripePrice
    // ========================================

    @Nested
    @DisplayName("recreateStripePrice")
    class RecreateStripePrice {

        @Test
        @DisplayName("正常系: 新しいダミーPrice IDが返る")
        void recreateStripePrice_正常_新PriceIDが返る() {
            // Given
            TicketProductEntity product = createProduct();

            // When
            String newPriceId = stripeTicketService.recreateStripePrice(product, "price_old_123");

            // Then
            assertThat(newPriceId).startsWith("price_placeholder_new_");
        }
    }

    // ========================================
    // deactivateStripeProduct
    // ========================================

    @Nested
    @DisplayName("deactivateStripeProduct")
    class DeactivateStripeProduct {

        @Test
        @DisplayName("正常系: 例外なく完了する")
        void deactivateStripeProduct_正常_例外なし() {
            // When / Then（例外が発生しないことを確認）
            stripeTicketService.deactivateStripeProduct("prod_placeholder_1");
        }
    }

    // ========================================
    // createCheckoutSession
    // ========================================

    @Nested
    @DisplayName("createCheckoutSession")
    class CreateCheckoutSession {

        @Test
        @DisplayName("正常系: Checkout Session結果が返る")
        void createCheckoutSession_正常_結果が返る() {
            // Given
            TicketProductEntity product = createProduct();
            Long userId = 200L;
            Long paymentId = 300L;
            Long bookId = 400L;
            String successUrl = "https://example.com/success";
            String cancelUrl = "https://example.com/cancel";

            // When
            StripeTicketService.CheckoutSessionResult result =
                    stripeTicketService.createCheckoutSession(product, userId, paymentId, bookId, successUrl, cancelUrl);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.checkoutUrl()).contains("checkout.stripe.com");
            assertThat(result.sessionId()).startsWith("cs_placeholder_");
            assertThat(result.expiresAt()).isNotNull();
        }

        @Test
        @DisplayName("正常系: セッションIDにpaymentIdが含まれる")
        void createCheckoutSession_sessionIdにpaymentId含む() {
            // Given
            TicketProductEntity product = createProduct();

            // When
            StripeTicketService.CheckoutSessionResult result =
                    stripeTicketService.createCheckoutSession(product, 1L, 999L, 1L, "url", "url");

            // Then
            assertThat(result.sessionId()).isEqualTo("cs_placeholder_999");
        }
    }

    // ========================================
    // createRefund
    // ========================================

    @Nested
    @DisplayName("createRefund")
    class CreateRefund {

        @Test
        @DisplayName("正常系: ダミーのRefund IDが返る")
        void createRefund_正常_ダミーRefundIDが返る() {
            // Given
            String paymentIntentId = "pi_test_12345";
            int amount = 5000;

            // When
            String refundId = stripeTicketService.createRefund(paymentIntentId, amount);

            // Then
            assertThat(refundId).isEqualTo("re_placeholder_pi_test_12345");
        }
    }
}
