package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.payment.dto.BulkPaymentRequest;
import com.mannschaft.app.payment.dto.BulkPaymentResponse;
import com.mannschaft.app.payment.dto.CreateManualPaymentRequest;
import com.mannschaft.app.payment.dto.MemberPaymentResponse;
import com.mannschaft.app.payment.dto.RemindResponse;
import com.mannschaft.app.payment.dto.UpdatePaymentRequest;
import com.mannschaft.app.payment.service.MemberPaymentService;
import com.mannschaft.app.payment.service.PaymentItemService;
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
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;

/**
 * 組織支払い記録コントローラー。組織単位の支払い記録管理 API を提供する。
 * <p>
 * エンドポイント数: 8（GET payments, POST payments, PATCH payments/{paymentId},
 *                     POST payments/bulk, DELETE payments/{paymentId},
 *                     POST remind, GET payments/export,
 *                     POST payments/{paymentId}/refund）
 *
 * <p><b>認可根治戦役 Wave3-B1（2026-07-16）:</b> 全 8 EP に認可ゼロ（未認証チェックのみ deny-by-default 頼み）
 * だった欠陥を是正する。変更系（create/update/bulk/cancel/remind/export/refund）は
 * {@link AccessControlService#checkAdminOrAbove}（"ORGANIZATION" scope）を要求し、閲覧系（list）は
 * {@link AccessControlService#checkMembership} を要求する。加えて、path 上位スコープの ADMIN であっても
 * {@code itemId} が別組織/別チームの支払い項目である BOLA（越境）を防ぐため、
 * {@link PaymentItemService#findByIdAndOrganizationIdOrThrow} で {@code itemId} が {@code id}（組織）配下に
 * 属することを検証してから {@link MemberPaymentService} の {@code itemId} 直渡しメソッドを呼ぶ。
 * 不一致は {@code PAYMENT_ITEM_NOT_FOUND}（404・存在秘匿）。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{id}/payment-items/{itemId}")
@Tag(name = "組織支払い記録", description = "F08.2 組織支払い記録管理")
@RequiredArgsConstructor
public class OrganizationPaymentController {

    private final MemberPaymentService memberPaymentService;
    private final PaymentItemService paymentItemService;
    private final AccessControlService accessControlService;


    @GetMapping("/payments")
    @Operation(summary = "組織メンバー支払い状況一覧")
    public ResponseEntity<PagedResponse<MemberPaymentResponse>> listPayments(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, id, "ORGANIZATION");
        paymentItemService.findByIdAndOrganizationIdOrThrow(itemId, id);
        Page<MemberPaymentResponse> result = memberPaymentService.listPayments(
                itemId, status, PageRequest.of(page, Math.min(size, 100)));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PostMapping("/payments")
    @Operation(summary = "組織手動支払い記録")
    public ResponseEntity<ApiResponse<MemberPaymentResponse>> createManualPayment(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @Valid @RequestBody CreateManualPaymentRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, id, "ORGANIZATION");
        paymentItemService.findByIdAndOrganizationIdOrThrow(itemId, id);
        MemberPaymentResponse response = memberPaymentService.createManualPayment(
                itemId, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @PatchMapping("/payments/{paymentId}")
    @Operation(summary = "組織支払い記録修正")
    public ResponseEntity<ApiResponse<MemberPaymentResponse>> updatePayment(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @PathVariable Long paymentId,
            @Valid @RequestBody UpdatePaymentRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, id, "ORGANIZATION");
        paymentItemService.findByIdAndOrganizationIdOrThrow(itemId, id);
        MemberPaymentResponse response = memberPaymentService.updatePayment(itemId, paymentId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PostMapping("/payments/bulk")
    @Operation(summary = "組織手動支払い一括記録")
    public ResponseEntity<ApiResponse<BulkPaymentResponse>> createBulkPayments(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @Valid @RequestBody BulkPaymentRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, id, "ORGANIZATION");
        paymentItemService.findByIdAndOrganizationIdOrThrow(itemId, id);
        BulkPaymentResponse response = memberPaymentService.createBulkPayments(
                itemId, userId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "既存の誤決済記録をGate状態にかかわらず取消可能にするため")
    @DeleteMapping("/payments/{paymentId}")
    @Operation(summary = "組織支払い記録取り消し")
    public ResponseEntity<Void> cancelPayment(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @PathVariable Long paymentId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, id, "ORGANIZATION");
        paymentItemService.findByIdAndOrganizationIdOrThrow(itemId, id);
        memberPaymentService.cancelPayment(itemId, paymentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/remind")
    @Operation(summary = "組織未払いリマインド送信")
    public ResponseEntity<ApiResponse<RemindResponse>> sendRemind(
            @PathVariable Long id,
            @PathVariable Long itemId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, id, "ORGANIZATION");
        paymentItemService.findByIdAndOrganizationIdOrThrow(itemId, id);
        RemindResponse response = memberPaymentService.sendRemind(itemId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @GetMapping("/payments/export")
    @Operation(summary = "組織支払い状況 CSV エクスポート")
    public ResponseEntity<byte[]> exportPayments(
            @PathVariable Long id,
            @PathVariable Long itemId) {
        Long userId = SecurityUtils.getCurrentUserId();
        // CSV エクスポートは全メンバーの財務明細一覧のため ADMIN 限定（TeamPaymentExportController と同水準）。
        accessControlService.checkAdminOrAbove(userId, id, "ORGANIZATION");
        paymentItemService.findByIdAndOrganizationIdOrThrow(itemId, id);
        byte[] csv = memberPaymentService.exportPaymentsCsv(itemId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payments_export.csv\"");
        return ResponseEntity.ok().headers(headers).body(csv);
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "既存決済の実返金義務をGate状態にかかわらず履行するため")
    @PostMapping("/payments/{paymentId}/refund")
    @Operation(summary = "組織支払い全額返金")
    public ResponseEntity<ApiResponse<MemberPaymentResponse>> refundPayment(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @PathVariable Long paymentId) {
        Long userId = SecurityUtils.getCurrentUserId();
        // 最重要案件: Stripe 実返金。ORG ADMIN 以上のみ＋itemId の組織帰属検証（BOLA 是正）を通す。
        accessControlService.checkAdminOrAbove(userId, id, "ORGANIZATION");
        paymentItemService.findByIdAndOrganizationIdOrThrow(itemId, id);
        MemberPaymentResponse response = memberPaymentService.refundPayment(
                itemId, paymentId, userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
