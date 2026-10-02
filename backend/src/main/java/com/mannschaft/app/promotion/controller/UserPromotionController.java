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
import com.mannschaft.app.common.security.SelfScopedEndpoint;

/**
 * ユーザー用プロモーション・クーポンコントローラー。
 */
@RestController
@Tag(name = "ユーザー用プロモーション・クーポン", description = "F09.2 ユーザー向けプロモーション・クーポンAPI")
@RequiredArgsConstructor
public class UserPromotionController {

    private final PromotionDeliveryService deliveryService;
    private final CouponService couponService;

    /**
     * <b>認可方式（{@link SelfScopedEndpoint} メソッド付与）</b>:
     * {@code deliveryService.listByUser} は {@code SecurityUtils.getCurrentUserId()} のみを
     * 検索条件に渡すため、他人宛のプロモーションへ到達する経路が構造的に無い
     * （UserPromotionController#listPromotions）。認可根治戦役 Wave6 監査済。
     */
    @SelfScopedEndpoint(
            "deliveryService.listByUser(userId, ...) は SecurityUtils.getCurrentUserId() のみを"
                    + "検索条件に渡す（UserPromotionController#listPromotions）")
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

    /**
     * <b>認可方式（{@link SelfScopedEndpoint} メソッド付与）</b>:
     * {@code deliveryService.markAsRead} 内部の {@code findByIdAndUserId(deliveryId, userId)} が
     * 検索条件に {@code SecurityUtils.getCurrentUserId()} を束縛するため、他人配信の既読化経路が
     * 構造的に無い（UserPromotionController#markAsRead）。認可根治戦役 Wave6 監査済。
     */
    @SelfScopedEndpoint(
            "deliveryService.markAsRead が findByIdAndUserId(deliveryId, userId) で"
                    + "SecurityUtils.getCurrentUserId() に束縛する（UserPromotionController#markAsRead）")
    @PatchMapping("/api/v1/users/me/promotions/{deliveryId}/read")
    @Operation(summary = "既読マーク")
    public ResponseEntity<Void> markAsRead(@PathVariable Long deliveryId) {
        deliveryService.markAsRead(SecurityUtils.getCurrentUserId(), deliveryId);
        return ResponseEntity.noContent().build();
    }

    /**
     * <b>認可方式（{@link SelfScopedEndpoint} メソッド付与）</b>:
     * {@code couponService.listUserCoupons} は {@code SecurityUtils.getCurrentUserId()} のみを
     * 検索条件に渡すため、他人のクーポンへ到達する経路が構造的に無い
     * （UserPromotionController#listCoupons）。認可根治戦役 Wave6 監査済。
     */
    @SelfScopedEndpoint(
            "couponService.listUserCoupons(userId) は SecurityUtils.getCurrentUserId() のみを"
                    + "検索条件に渡す（UserPromotionController#listCoupons）")
    @GetMapping("/api/v1/users/me/coupons")
    @Operation(summary = "保有クーポン一覧")
    public ResponseEntity<ApiResponse<List<UserCouponResponse>>> listCoupons() {
        return ResponseEntity.ok(ApiResponse.of(couponService.listUserCoupons(SecurityUtils.getCurrentUserId())));
    }

    /**
     * <b>認可方式（{@link SelfScopedEndpoint} メソッド付与）</b>:
     * {@code couponService.redeem} 内部の {@code findByIdAndUserId(distributionId, userId)} が
     * 検索条件に {@code SecurityUtils.getCurrentUserId()} を束縛するため、他人配布クーポンの
     * 利用経路が構造的に無い（UserPromotionController#redeemCoupon）。認可根治戦役 Wave6 監査済。
     */
    @SelfScopedEndpoint(
            "couponService.redeem が findByIdAndUserId(distributionId, userId) で"
                    + "SecurityUtils.getCurrentUserId() に束縛する（UserPromotionController#redeemCoupon）")
    @PostMapping("/api/v1/users/me/coupons/{distributionId}/redeem")
    @Operation(summary = "クーポン利用")
    public ResponseEntity<Void> redeemCoupon(
            @PathVariable Long distributionId,
            @RequestBody RedeemCouponRequest request) {
        couponService.redeem(SecurityUtils.getCurrentUserId(), distributionId, request);
        return ResponseEntity.noContent().build();
    }
}
