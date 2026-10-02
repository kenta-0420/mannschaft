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
 * チーム支払い項目コントローラー。チーム単位の支払い項目 CRUD を提供する。
 * <p>
 * エンドポイント数: 4（GET, POST, PATCH, DELETE）
 *
 * <p><b>認可根治戦役 Wave6（B3・2026-07-21）:</b> 双子の {@link OrganizationPaymentItemController}
 * （Wave5早馬B1b で敷設済み）と同水準へ揃える。閲覧系（GET）は
 * {@link AccessControlService#checkMembership}、変更系（POST/PATCH/DELETE）は
 * {@link AccessControlService#checkAdminOrAbove} を "TEAM" スコープで要求する。
 * {@code itemId} のチーム帰属は {@link PaymentItemService#updateTeamPaymentItem} /
 * {@link PaymentItemService#deleteTeamPaymentItem} が {@code findByIdAndTeamId} で検証するため
 * 追加ゲートは不要（他チーム所属の {@code itemId} は {@code PAYMENT_ITEM_NOT_FOUND}・404）。
 * 変更前の状態に関する詳細はマージ後に戦役台帳へ記録する。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{id}/payment-items")
@Tag(name = "チーム支払い項目", description = "F08.2 チーム支払い項目 CRUD")
@RequiredArgsConstructor
public class TeamPaymentItemController {

    private final PaymentItemService paymentItemService;
    private final AccessControlService accessControlService;


    /**
     * チーム支払い項目一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "チーム支払い項目一覧")
    public ResponseEntity<PagedResponse<PaymentItemResponse>> listPaymentItems(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, id, "TEAM");
        Page<PaymentItemResponse> result = paymentItemService.listTeamPaymentItems(id, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * チーム支払い項目を作成する。
     */
    @PostMapping
    @Operation(summary = "チーム支払い項目作成")
    public ResponseEntity<ApiResponse<PaymentItemResponse>> createPaymentItem(
            @PathVariable Long id,
            @Valid @RequestBody CreatePaymentItemRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, id, "TEAM");
        PaymentItemResponse response = paymentItemService.createTeamPaymentItem(id, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * チーム支払い項目を更新する。
     */
    @PatchMapping("/{itemId}")
    @Operation(summary = "チーム支払い項目更新")
    public ResponseEntity<ApiResponse<PaymentItemResponse>> updatePaymentItem(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @Valid @RequestBody UpdatePaymentItemRequest request) {
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, "TEAM");
        PaymentItemResponse response = paymentItemService.updateTeamPaymentItem(id, itemId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チーム支払い項目を論理削除する。
     */
    @DeleteMapping("/{itemId}")
    @Operation(summary = "チーム支払い項目削除")
    public ResponseEntity<Void> deletePaymentItem(
            @PathVariable Long id,
            @PathVariable Long itemId) {
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, "TEAM");
        paymentItemService.deleteTeamPaymentItem(id, itemId);
        return ResponseEntity.noContent().build();
    }
}
