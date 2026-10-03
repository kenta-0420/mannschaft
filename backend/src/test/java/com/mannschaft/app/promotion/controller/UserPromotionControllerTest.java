package com.mannschaft.app.promotion.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.promotion.dto.RedeemCouponRequest;
import com.mannschaft.app.promotion.dto.UserCouponResponse;
import com.mannschaft.app.promotion.dto.UserPromotionResponse;
import com.mannschaft.app.promotion.service.CouponService;
import com.mannschaft.app.promotion.service.PromotionDeliveryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link UserPromotionController} の単体テスト。
 *
 * <p>listPromotions / listCoupons / markAsRead / redeemCoupon いずれも
 * {@code SecurityUtils.getCurrentUserId()} を検索・更新の主体として渡し、
 * サービス層のリポジトリクエリ（{@code findByIdAndUserId} 等）が本人分にのみ束縛するため、
 * 他人のプロモーション・クーポンへ到達する経路が構造的に無い。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserPromotionController 単体テスト")
class UserPromotionControllerTest {

    @Mock
    private PromotionDeliveryService deliveryService;

    @Mock
    private CouponService couponService;

    @InjectMocks
    private UserPromotionController controller;

    private static final Long USER_ID = 100L;
    private static final Long DELIVERY_ID = 5L;
    private static final Long DISTRIBUTION_ID = 7L;

    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void setUpSecurityUtils() {
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDownSecurityUtils() {
        securityUtils.close();
    }

    @Test
    @DisplayName("UserPromotionController#listPromotions は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
    void listPromotions_boundToCurrentUserOnly() {
        Page<UserPromotionResponse> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        given(deliveryService.listByUser(org.mockito.ArgumentMatchers.eq(USER_ID), any())).willReturn(page);

        ResponseEntity<PagedResponse<UserPromotionResponse>> result = controller.listPromotions(0, 20);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(deliveryService).listByUser(org.mockito.ArgumentMatchers.eq(USER_ID), any());
    }

    @Test
    @DisplayName("UserPromotionController#markAsRead は SecurityUtils.getCurrentUserId() のみを更新条件に渡す")
    void markAsRead_boundToCurrentUserOnly() {
        ResponseEntity<Void> result = controller.markAsRead(DELIVERY_ID);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // 他人の userId を更新条件に渡す経路が存在しないことの裏取り。
        verify(deliveryService).markAsRead(USER_ID, DELIVERY_ID);
    }

    @Test
    @DisplayName("UserPromotionController#listCoupons は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
    void listCoupons_boundToCurrentUserOnly() {
        UserCouponResponse coupon = Mockito.mock(UserCouponResponse.class);
        given(couponService.listUserCoupons(USER_ID)).willReturn(List.of(coupon));

        ResponseEntity<ApiResponse<List<UserCouponResponse>>> result = controller.listCoupons();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(couponService).listUserCoupons(USER_ID);
    }

    @Test
    @DisplayName("UserPromotionController#redeemCoupon は SecurityUtils.getCurrentUserId() のみを更新条件に渡す")
    void redeemCoupon_boundToCurrentUserOnly() {
        RedeemCouponRequest request = new RedeemCouponRequest("店頭利用");

        ResponseEntity<Void> result = controller.redeemCoupon(DISTRIBUTION_ID, request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // 他人の userId を更新条件に渡す経路が存在しないことの裏取り。
        verify(couponService).redeem(USER_ID, DISTRIBUTION_ID, request);
    }
}
