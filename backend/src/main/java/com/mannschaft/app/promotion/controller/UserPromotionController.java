package com.mannschaft.app.promotion.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.promotion.dto.RedeemCouponRequest;
import com.mannschaft.app.promotion.dto.UserCouponResponse;
import com.mannschaft.app.promotion.dto.UserPromotionResponse;
import com.mannschaft.app.promotion.service.CouponService;
import com.mannschaft.app.promotion.service.PromotionDeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * ユーザー用プロモーション・クーポンコントローラー。
 */
@RestController
@Tag(name = "ユーザー用プロモーション・クーポン", description = "F09.2 ユーザー向けプロモーション・クーポンAPI")
@RequiredArgsConstructor
public class UserPromotionController {

    private final PromotionDeliveryService deliveryService;
    private final CouponService couponService;

    @GetMapping("/api/v1/users/me/promotions")
    @Operation(summary = "受信プロモーション一覧")
    public ResponseEntity<PagedResponse<UserPromotionResponse>> listPromotions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<UserPromotionResponse> result = deliveryService.listByUser(
                SecurityUtils.getCurrentUserId(), PageRequest.of(page, Math.min(size, 50)));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PatchMapping("/api/v1/users/me/promotions/{deliveryId}/read")
    @Operation(summary = "既読マーク")
    public ResponseEntity<Void> markAsRead(@PathVariable Long deliveryId) {
        deliveryService.markAsRead(SecurityUtils.getCurrentUserId(), deliveryId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/users/me/coupons")
    @Operation(summary = "保有クーポン一覧")
    public ResponseEntity<ApiResponse<List<UserCouponResponse>>> listCoupons() {
        return ResponseEntity.ok(ApiResponse.of(couponService.listUserCoupons(SecurityUtils.getCurrentUserId())));
    }

    @PostMapping("/api/v1/users/me/coupons/{distributionId}/redeem")
    @Operation(summary = "クーポン利用")
    public ResponseEntity<Void> redeemCoupon(
            @PathVariable Long distributionId,
            @RequestBody RedeemCouponRequest request) {
        couponService.redeem(SecurityUtils.getCurrentUserId(), distributionId, request);
        return ResponseEntity.noContent().build();
    }
}
