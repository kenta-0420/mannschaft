package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.payment.dto.MemberPaymentResponse;
import com.mannschaft.app.payment.dto.PaymentRequirementResponse;
import com.mannschaft.app.payment.service.MemberPaymentService;
import com.mannschaft.app.payment.service.PaymentRequirementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 自分の支払いコントローラー。ログインユーザー自身の支払い状況・未払い要件を提供する。
 * <p>
 * エンドポイント数: 3（GET me/payments, GET me/payment-requirements, GET me/subscriptions [Phase4]）
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "自分の支払い", description = "F08.2 自分の支払い状況・未払い要件")
@RequiredArgsConstructor
public class MyPaymentController {

    private final MemberPaymentService memberPaymentService;
    private final PaymentRequirementService paymentRequirementService;

    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 自分の支払い状況一覧を取得する。
     */
    @GetMapping("/payments")
    @Operation(summary = "自分の支払い状況一覧")
    public ResponseEntity<PagedResponse<MemberPaymentResponse>> listMyPayments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<MemberPaymentResponse> result = memberPaymentService.listMyPayments(
                getCurrentUserId(), PageRequest.of(page, Math.min(size, 100)));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 自分に課されている未払い項目一覧を取得する。
     */
    @GetMapping("/payment-requirements")
    @Operation(summary = "未払い項目一覧")
    public ResponseEntity<ApiResponse<List<PaymentRequirementResponse>>> getPaymentRequirements() {
        List<PaymentRequirementResponse> requirements =
                paymentRequirementService.getPaymentRequirements(getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(requirements));
    }

    /**
     * 自分の有効サブスクリプション一覧を取得する（Phase 4）。
     */
    @GetMapping("/subscriptions")
    @Operation(summary = "自分のサブスクリプション一覧（Phase 4）")
    public ResponseEntity<ApiResponse<List<Object>>> listMySubscriptions() {
        // Phase 4 実装予定
        return ResponseEntity.ok(ApiResponse.of(List.of()));
    }
}
