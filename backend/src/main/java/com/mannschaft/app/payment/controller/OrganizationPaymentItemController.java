package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.AccessControlService;
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
 *
 * <p><b>認可根治戦役 Wave5早馬（B1b・2026-07-17）:</b> 兄弟 {@link OrganizationPaymentController}
 * は Wave3-B1 で全 EP に {@link AccessControlService} を敷設済みだったが、本コントローラは
 * 未注入のまま素通りしていた（他組織の支払い項目を論理削除できる欠陥）。兄弟と同型で、
 * 閲覧系（GET）は {@link AccessControlService#checkMembership}、変更系（POST/PATCH/DELETE）は
 * {@link AccessControlService#checkAdminOrAbove} を要求する。{@code itemId} の組織帰属は
 * {@link PaymentItemService#updateOrganizationPaymentItem}/{@link PaymentItemService#deleteOrganizationPaymentItem}
 * が {@code findByIdAndOrganizationId} で既に検証しているため、BOLA 対応の追加ゲートは不要
 * （存在しない/他組織所属の {@code itemId} は既存どおり {@code PAYMENT_ITEM_NOT_FOUND}・404）。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{id}/payment-items")
@Tag(name = "組織支払い項目", description = "F08.2 組織支払い項目 CRUD")
@RequiredArgsConstructor
public class OrganizationPaymentItemController {

    private final PaymentItemService paymentItemService;
    private final AccessControlService accessControlService;


    @GetMapping
    @Operation(summary = "組織支払い項目一覧")
    public ResponseEntity<PagedResponse<PaymentItemResponse>> listPaymentItems(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, id, "ORGANIZATION");
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
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, id, "ORGANIZATION");
        PaymentItemResponse response = paymentItemService.createOrganizationPaymentItem(id, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @PatchMapping("/{itemId}")
    @Operation(summary = "組織支払い項目更新")
    public ResponseEntity<ApiResponse<PaymentItemResponse>> updatePaymentItem(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @Valid @RequestBody UpdatePaymentItemRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, id, "ORGANIZATION");
        PaymentItemResponse response = paymentItemService.updateOrganizationPaymentItem(id, itemId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @DeleteMapping("/{itemId}")
    @Operation(summary = "組織支払い項目削除")
    public ResponseEntity<Void> deletePaymentItem(
            @PathVariable Long id,
            @PathVariable Long itemId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, id, "ORGANIZATION");
        paymentItemService.deleteOrganizationPaymentItem(id, itemId);
        return ResponseEntity.noContent().build();
    }
}
