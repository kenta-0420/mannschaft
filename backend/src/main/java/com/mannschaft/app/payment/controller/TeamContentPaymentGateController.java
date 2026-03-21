package com.mannschaft.app.payment.controller;

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

/**
 * チームコンテンツゲートコントローラー。チーム内コンテンツのアクセスゲート管理を提供する。
 * <p>
 * エンドポイント数: 2（GET, PUT）
 */
@RestController
@RequestMapping("/api/v1/teams/{id}/content-payment-gates")
@Tag(name = "チームコンテンツゲート", description = "F08.2 チームコンテンツアクセスゲート")
@RequiredArgsConstructor
public class TeamContentPaymentGateController {

    private final ContentPaymentGateService contentPaymentGateService;

    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * チーム内のコンテンツゲート一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "チームコンテンツゲート一覧")
    public ResponseEntity<PagedResponse<ContentPaymentGateResponse>> listContentGates(
            @PathVariable Long id,
            @RequestParam(required = false) String contentType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Page<ContentPaymentGateResponse> result = contentPaymentGateService.listTeamContentGates(
                id, contentType, PageRequest.of(page, Math.min(size, 100)));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * チーム内のコンテンツゲートを一括設定する。
     */
    @PutMapping
    @Operation(summary = "チームコンテンツゲート一括設定")
    public ResponseEntity<ApiResponse<ContentGateSetResponse>> setContentGates(
            @PathVariable Long id,
            @Valid @RequestBody ContentPaymentGateRequest request) {
        ContentGateSetResponse response = contentPaymentGateService.setTeamContentGates(
                id, getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
