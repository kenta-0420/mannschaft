package com.mannschaft.app.promotion.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.promotion.dto.CouponResponse;
import com.mannschaft.app.promotion.dto.CreateCouponRequest;
import com.mannschaft.app.promotion.service.CouponService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 組織クーポン管理コントローラー。
 */
@RestController
@Tag(name = "クーポン管理（組織）", description = "F09.2 組織クーポンCRUD")
@RequiredArgsConstructor
public class OrgCouponController {

    private final CouponService couponService;

    @GetMapping("/api/v1/organizations/{orgId}/coupons")
    @Operation(summary = "クーポン一覧")
    public ResponseEntity<PagedResponse<CouponResponse>> list(
            @PathVariable Long orgId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<CouponResponse> result = couponService.list("ORGANIZATION", orgId, PageRequest.of(page, Math.min(size, 50)));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PostMapping("/api/v1/organizations/{orgId}/coupons")
    @Operation(summary = "クーポン作成")
    public ResponseEntity<ApiResponse<CouponResponse>> create(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateCouponRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.of(couponService.create("ORGANIZATION", orgId, SecurityUtils.getCurrentUserId(), request)));
    }

    @GetMapping("/api/v1/organizations/{orgId}/coupons/{id}")
    @Operation(summary = "クーポン詳細")
    public ResponseEntity<ApiResponse<CouponResponse>> get(
            @PathVariable Long orgId, @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(couponService.get("ORGANIZATION", orgId, id)));
    }

    @PutMapping("/api/v1/organizations/{orgId}/coupons/{id}")
    @Operation(summary = "クーポン更新")
    public ResponseEntity<ApiResponse<CouponResponse>> update(
            @PathVariable Long orgId, @PathVariable Long id,
            @Valid @RequestBody CreateCouponRequest request) {
        return ResponseEntity.ok(ApiResponse.of(couponService.update("ORGANIZATION", orgId, id, request)));
    }

    @DeleteMapping("/api/v1/organizations/{orgId}/coupons/{id}")
    @Operation(summary = "クーポン削除")
    public ResponseEntity<Void> delete(@PathVariable Long orgId, @PathVariable Long id) {
        couponService.delete("ORGANIZATION", orgId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/api/v1/organizations/{orgId}/coupons/{id}/toggle")
    @Operation(summary = "クーポン有効/無効切替")
    public ResponseEntity<ApiResponse<CouponResponse>> toggle(
            @PathVariable Long orgId, @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(couponService.toggle("ORGANIZATION", orgId, id)));
    }
}
