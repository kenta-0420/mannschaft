package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.payment.dto.CheckoutResponse;
import com.mannschaft.app.payment.service.MemberPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 決済チェックアウトコントローラー。Stripe Checkout セッション作成を提供する。
 * <p>
 * エンドポイント数: 1（POST checkout）
 */
@RestController
@RequestMapping("/api/v1/payment-items/{itemId}")
@Tag(name = "決済チェックアウト", description = "F08.2 Stripe Checkout セッション")
@RequiredArgsConstructor
public class PaymentCheckoutController {

    private final MemberPaymentService memberPaymentService;

    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * Stripe Checkout セッションを作成する（自己支払い・寄付）。
     */
    @PostMapping("/checkout")
    @Operation(summary = "Stripe Checkout セッション作成")
    public ResponseEntity<ApiResponse<CheckoutResponse>> createCheckout(
            @PathVariable Long itemId) {
        CheckoutResponse response = memberPaymentService.createCheckout(itemId, getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
