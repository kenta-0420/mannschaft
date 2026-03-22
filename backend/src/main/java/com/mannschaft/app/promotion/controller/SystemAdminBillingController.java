package com.mannschaft.app.promotion.controller;

import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.promotion.dto.BillingRecordResponse;
import com.mannschaft.app.promotion.service.PromotionBillingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SYSTEM_ADMIN用プロモーション課金コントローラー。
 */
@RestController
@Tag(name = "プロモーション課金（SYSTEM_ADMIN）", description = "F09.2 課金状況一覧")
@RequiredArgsConstructor
public class SystemAdminBillingController {

    private final PromotionBillingService billingService;

    @GetMapping("/api/v1/system-admin/promotion-billing")
    @Operation(summary = "課金状況一覧")
    public ResponseEntity<PagedResponse<BillingRecordResponse>> list(
            @RequestParam(required = false) String billingStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<BillingRecordResponse> result = billingService.listBillingRecords(
                billingStatus, PageRequest.of(page, Math.min(size, 50)));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }
}
