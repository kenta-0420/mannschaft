package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.payment.dto.PaymentSummaryResponse;
import com.mannschaft.app.payment.service.PaymentSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 組織支払いサマリーコントローラー。
 * <p>
 * エンドポイント数: 1（GET payment-summary）
 */
@RestController
@RequestMapping("/api/v1/organizations/{id}")
@Tag(name = "組織支払いサマリー", description = "F08.2 組織支払いサマリー")
@RequiredArgsConstructor
public class OrganizationPaymentSummaryController {

    private final PaymentSummaryService paymentSummaryService;

    @GetMapping("/payment-summary")
    @Operation(summary = "組織支払いサマリー")
    public ResponseEntity<ApiResponse<PaymentSummaryResponse>> getPaymentSummary(
            @PathVariable Long id) {
        PaymentSummaryResponse response = paymentSummaryService.getOrganizationPaymentSummary(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
