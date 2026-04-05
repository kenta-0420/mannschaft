package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.payment.dto.ReconcileResponse;
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
 * SYSTEM_ADMIN 専用支払い管理コントローラー。Stripe 手動再同期を提供する。
 * <p>
 * エンドポイント数: 1（POST admin/stripe/reconcile/{paymentId}）
 */
@RestController
@RequestMapping("/api/v1/admin/stripe")
@Tag(name = "管理者支払い操作", description = "F08.2 SYSTEM_ADMIN 専用支払い管理")
@RequiredArgsConstructor
public class AdminPaymentController {

    private final MemberPaymentService memberPaymentService;

    /**
     * Stripe 状態との手動再同期を実行する。
     */
    @PostMapping("/reconcile/{paymentId}")
    @Operation(summary = "Stripe 手動再同期")
    public ResponseEntity<ApiResponse<ReconcileResponse>> reconcile(
            @PathVariable Long paymentId) {
        ReconcileResponse response = memberPaymentService.reconcile(paymentId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
