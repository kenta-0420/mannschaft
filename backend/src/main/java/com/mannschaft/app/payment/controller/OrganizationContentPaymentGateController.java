package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.payment.dto.ContentGateSetResponse;
import com.mannschaft.app.payment.dto.ContentPaymentGateRequest;
import com.mannschaft.app.payment.dto.ContentPaymentGateResponse;
import com.mannschaft.app.payment.service.ContentPaymentGateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 組織コンテンツゲートコントローラー。組織内コンテンツのアクセスゲート管理を提供する。
 * <p>
 * エンドポイント数: 2（GET, PUT）
 *
 * <p><b>認可根治戦役 Wave5早馬（B1b・2026-07-17）:</b> 兄弟 {@link OrganizationPaymentController}
 * は Wave3-B1 で全 EP に {@link AccessControlService} を敷設済みだったが、本コントローラは
 * 未注入のまま素通りしていた（組織認可無しでコンテンツゲートを全消去できる欠陥）。兄弟と同型で、
 * 閲覧系（GET）は {@link AccessControlService#checkMembership}、変更系（PUT）は
 * {@link AccessControlService#checkAdminOrAbove} を要求する。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{id}/content-payment-gates")
@Tag(name = "組織コンテンツゲート", description = "F08.2 組織コンテンツアクセスゲート")
@RequiredArgsConstructor
public class OrganizationContentPaymentGateController {

    private final ContentPaymentGateService contentPaymentGateService;
    private final AccessControlService accessControlService;


    @GetMapping
    @Operation(summary = "組織コンテンツゲート一覧")
    public ResponseEntity<PagedResponse<ContentPaymentGateResponse>> listContentGates(
            @PathVariable Long id,
            @RequestParam(required = false) String contentType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, id, "ORGANIZATION");
        Page<ContentPaymentGateResponse> result = contentPaymentGateService.listOrganizationContentGates(
                id, contentType, PageRequest.of(page, Math.min(size, 100)));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PutMapping
    @Operation(summary = "組織コンテンツゲート一括設定")
    public ResponseEntity<ApiResponse<ContentGateSetResponse>> setContentGates(
            @PathVariable Long id,
            @Valid @RequestBody ContentPaymentGateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, id, "ORGANIZATION");
        ContentGateSetResponse response = contentPaymentGateService.setOrganizationContentGates(
                id, userId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
