package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.PaymentRequestStatus;
import com.mannschaft.app.payment.dto.CreatePaymentRequestRequest;
import com.mannschaft.app.payment.dto.PaymentRequestResponse;
import com.mannschaft.app.payment.entity.PaymentRequestEntity;
import com.mannschaft.app.payment.service.PaymentRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F08.9 P7 第二波: 協会（ORG）視点の協会請求コントローラー（02_api §7）。
 *
 * <p>協会 ADMIN が請求を発行（DRAFT）・配信（SENT・確認必須通知一斉送信）・取消（CANCELLED）し、
 * 自分の発行請求を一覧する。認可は {@link PaymentRequestService} 内部の {@code requireOrgAdmin}
 * （{@code AccessControlService} 経由）で行う（既存 payment コントローラー同様にコントローラーは薄く保つ）。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/payment-requests")
@Tag(name = "協会請求（協会視点）", description = "F08.9 P7 協会→加盟チーム請求の発行・配信・取消・一覧")
@RequiredArgsConstructor
public class PaymentRequestOrgController {

    private static final int MAX_PAGE_SIZE = 100;

    private final PaymentRequestService paymentRequestService;

    /**
     * 請求を発行する（DRAFT 起票）。
     */
    @PostMapping
    @Operation(summary = "協会請求の発行（DRAFT）")
    public ResponseEntity<ApiResponse<PaymentRequestResponse>> create(
            @PathVariable Long orgId,
            @Valid @RequestBody CreatePaymentRequestRequest request) {
        PaymentRequestEntity created = paymentRequestService.create(
                orgId, SecurityUtils.getCurrentUserId(), request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(PaymentRequestResponse.from(created)));
    }

    /**
     * 請求を配信する（DRAFT → SENT・確認必須通知を請求先チーム ADMIN へ一斉配信）。
     */
    @PatchMapping("/{id}/send")
    @Operation(summary = "協会請求の配信（SENT・通知一斉送信）")
    public ResponseEntity<ApiResponse<PaymentRequestResponse>> send(
            @PathVariable Long orgId,
            @PathVariable UUID id) {
        PaymentRequestEntity sent = paymentRequestService.send(orgId, id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(PaymentRequestResponse.from(sent)));
    }

    /**
     * 請求を取消する（DRAFT/SENT → CANCELLED）。
     */
    @PatchMapping("/{id}/cancel")
    @Operation(summary = "協会請求の取消（CANCELLED）")
    public ResponseEntity<ApiResponse<PaymentRequestResponse>> cancel(
            @PathVariable Long orgId,
            @PathVariable UUID id) {
        PaymentRequestEntity cancelled = paymentRequestService.cancel(orgId, id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(PaymentRequestResponse.from(cancelled)));
    }

    /**
     * 協会が発行した請求一覧を取得する（status フィルタ・ページング・新しい順）。
     */
    @GetMapping
    @Operation(summary = "協会の発行請求一覧（status フィルタ・ページング）")
    public ResponseEntity<PagedResponse<PaymentRequestResponse>> list(
            @PathVariable Long orgId,
            @RequestParam(required = false) List<PaymentRequestStatus> status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<PaymentRequestEntity> result = paymentRequestService.findForOrg(
                orgId, SecurityUtils.getCurrentUserId(), status, pageable);
        List<PaymentRequestResponse> content = result.getContent().stream()
                .map(PaymentRequestResponse::from)
                .toList();
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(content, meta));
    }
}
