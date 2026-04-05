package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.payment.dto.CreatePaymentItemRequest;
import com.mannschaft.app.payment.dto.PaymentItemResponse;
import com.mannschaft.app.payment.dto.UpdatePaymentItemRequest;
import com.mannschaft.app.payment.service.PaymentItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
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
 * 組織支払い項目コントローラー。組織単位の支払い項目 CRUD を提供する。
 * <p>
 * エンドポイント数: 4（GET, POST, PATCH, DELETE）
 */
@RestController
@RequestMapping("/api/v1/organizations/{id}/payment-items")
@Tag(name = "組織支払い項目", description = "F08.2 組織支払い項目 CRUD")
@RequiredArgsConstructor
public class OrganizationPaymentItemController {

    private final PaymentItemService paymentItemService;


    @GetMapping
    @Operation(summary = "組織支払い項目一覧")
    public ResponseEntity<PagedResponse<PaymentItemResponse>> listPaymentItems(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PaymentItemResponse> result = paymentItemService.listOrganizationPaymentItems(id, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PostMapping
    @Operation(summary = "組織支払い項目作成")
    public ResponseEntity<ApiResponse<PaymentItemResponse>> createPaymentItem(
            @PathVariable Long id,
            @Valid @RequestBody CreatePaymentItemRequest request) {
        PaymentItemResponse response = paymentItemService.createOrganizationPaymentItem(id, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @PatchMapping("/{itemId}")
    @Operation(summary = "組織支払い項目更新")
    public ResponseEntity<ApiResponse<PaymentItemResponse>> updatePaymentItem(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @Valid @RequestBody UpdatePaymentItemRequest request) {
        PaymentItemResponse response = paymentItemService.updateOrganizationPaymentItem(id, itemId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @DeleteMapping("/{itemId}")
    @Operation(summary = "組織支払い項目削除")
    public ResponseEntity<Void> deletePaymentItem(
            @PathVariable Long id,
            @PathVariable Long itemId) {
        paymentItemService.deleteOrganizationPaymentItem(id, itemId);
        return ResponseEntity.noContent().build();
    }
}
