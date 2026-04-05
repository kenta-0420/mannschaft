package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.payment.dto.BulkPaymentRequest;
import com.mannschaft.app.payment.dto.BulkPaymentResponse;
import com.mannschaft.app.payment.dto.CreateManualPaymentRequest;
import com.mannschaft.app.payment.dto.MemberPaymentResponse;
import com.mannschaft.app.payment.dto.RemindResponse;
import com.mannschaft.app.payment.dto.UpdatePaymentRequest;
import com.mannschaft.app.payment.service.MemberPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * チーム支払い記録コントローラー。チーム単位の支払い記録管理 API を提供する。
 * <p>
 * エンドポイント数: 9（GET payments, POST payments, PATCH payments/{paymentId},
 *                     POST payments/bulk, DELETE payments/{paymentId},
 *                     POST remind, GET payments/export, GET payment-summary,
 *                     POST payments/{paymentId}/refund）
 */
@RestController
@RequestMapping("/api/v1/teams/{id}/payment-items/{itemId}")
@Tag(name = "チーム支払い記録", description = "F08.2 チーム支払い記録管理")
@RequiredArgsConstructor
public class TeamPaymentController {

    private final MemberPaymentService memberPaymentService;


    /**
     * メンバー支払い状況一覧を取得する。
     */
    @GetMapping("/payments")
    @Operation(summary = "メンバー支払い状況一覧")
    public ResponseEntity<PagedResponse<MemberPaymentResponse>> listPayments(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Page<MemberPaymentResponse> result = memberPaymentService.listPayments(
                itemId, status, PageRequest.of(page, Math.min(size, 100)));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 手動支払い記録を作成する。
     */
    @PostMapping("/payments")
    @Operation(summary = "手動支払い記録")
    public ResponseEntity<ApiResponse<MemberPaymentResponse>> createManualPayment(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @Valid @RequestBody CreateManualPaymentRequest request) {
        MemberPaymentResponse response = memberPaymentService.createManualPayment(
                itemId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 支払い記録を修正する。
     */
    @PatchMapping("/payments/{paymentId}")
    @Operation(summary = "支払い記録修正")
    public ResponseEntity<ApiResponse<MemberPaymentResponse>> updatePayment(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @PathVariable Long paymentId,
            @Valid @RequestBody UpdatePaymentRequest request) {
        MemberPaymentResponse response = memberPaymentService.updatePayment(itemId, paymentId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 手動支払い一括記録を作成する。
     */
    @PostMapping("/payments/bulk")
    @Operation(summary = "手動支払い一括記録")
    public ResponseEntity<ApiResponse<BulkPaymentResponse>> createBulkPayments(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @Valid @RequestBody BulkPaymentRequest request) {
        BulkPaymentResponse response = memberPaymentService.createBulkPayments(
                itemId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 支払い記録を取り消す。
     */
    @DeleteMapping("/payments/{paymentId}")
    @Operation(summary = "支払い記録取り消し")
    public ResponseEntity<Void> cancelPayment(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @PathVariable Long paymentId) {
        memberPaymentService.cancelPayment(itemId, paymentId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 未払いメンバーへリマインドを送信する。
     */
    @PostMapping("/remind")
    @Operation(summary = "未払いリマインド送信")
    public ResponseEntity<ApiResponse<RemindResponse>> sendRemind(
            @PathVariable Long id,
            @PathVariable Long itemId) {
        RemindResponse response = memberPaymentService.sendRemind(itemId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 支払い状況を CSV エクスポートする。
     */
    @GetMapping("/payments/export")
    @Operation(summary = "支払い状況 CSV エクスポート")
    public ResponseEntity<byte[]> exportPayments(
            @PathVariable Long id,
            @PathVariable Long itemId) {
        byte[] csv = memberPaymentService.exportPaymentsCsv(itemId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payments_export.csv\"");
        return ResponseEntity.ok().headers(headers).body(csv);
    }

    /**
     * 全額返金を実行する。
     */
    @PostMapping("/payments/{paymentId}/refund")
    @Operation(summary = "全額返金実行")
    public ResponseEntity<ApiResponse<MemberPaymentResponse>> refundPayment(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @PathVariable Long paymentId) {
        MemberPaymentResponse response = memberPaymentService.refundPayment(
                itemId, paymentId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
