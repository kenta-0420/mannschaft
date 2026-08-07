package com.mannschaft.app.notification.credit.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.notification.credit.dto.NotificationCreditBalanceResponse;
import com.mannschaft.app.notification.credit.dto.NotificationCreditCheckoutRequest;
import com.mannschaft.app.notification.credit.dto.NotificationCreditCheckoutResponse;
import com.mannschaft.app.notification.credit.dto.NotificationCreditPackageResponse;
import com.mannschaft.app.notification.credit.dto.NotificationCreditPurchaseResponse;
import com.mannschaft.app.notification.credit.service.NotificationCreditCheckoutService;
import com.mannschaft.app.notification.credit.service.NotificationCreditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F09.13 通知プリペイドクレジットコントローラー。
 *
 * <p>エンドポイント一覧:</p>
 * <ul>
 *   <li>GET  /api/v1/organizations/{orgId}/notification-credits/balance   — ADMIN以上</li>
 *   <li>GET  /api/v1/organizations/{orgId}/notification-credits/purchases  — ADMIN以上</li>
 *   <li>GET  /api/v1/notification-credits/packages                        — 認証済みユーザー</li>
 *   <li>POST /api/v1/organizations/{orgId}/notification-credits/checkout   — ADMINのみ</li>
 * </ul>
 */
@RestController
@Tag(name = "通知クレジット", description = "F09.13 通知プリペイドクレジット管理")
@RequiredArgsConstructor
public class NotificationCreditController {

    private final NotificationCreditService creditService;
    private final NotificationCreditCheckoutService checkoutService;
    private final AccessControlService accessControlService;

    /**
     * 組織の通知クレジット残高を取得する（ADMIN以上）。
     *
     * @param orgId 組織ID
     * @return 残高レスポンス
     */
    @GetMapping("/api/v1/organizations/{orgId}/notification-credits/balance")
    @Operation(summary = "通知クレジット残高取得")
    public ResponseEntity<ApiResponse<NotificationCreditBalanceResponse>> getBalance(
            @PathVariable Long orgId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");
        NotificationCreditBalanceResponse response = creditService.getBalance(orgId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 組織の購入履歴一覧を取得する（ADMIN以上）。
     *
     * @param orgId 組織ID
     * @return 購入履歴レスポンスリスト
     */
    @GetMapping("/api/v1/organizations/{orgId}/notification-credits/purchases")
    @Operation(summary = "通知クレジット購入履歴")
    public ResponseEntity<ApiResponse<List<NotificationCreditPurchaseResponse>>> listPurchases(
            @PathVariable Long orgId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");
        List<NotificationCreditPurchaseResponse> response = creditService.listPurchases(orgId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 販売中パッケージ一覧を取得する（認証済みユーザー）。
     *
     * @return パッケージレスポンスリスト
     */
    // SecurityConfig.java:457 の anyRequest().authenticated() で認証必須。応答は販売中パッケージの
    // マスタ情報のみで、全認証済みユーザーに同一の結果を返す（利用者固有情報を含まない）。
    @AuthorizedByPathConfig
    @GetMapping("/api/v1/notification-credits/packages")
    @Operation(summary = "通知クレジットパッケージ一覧")
    public ResponseEntity<ApiResponse<List<NotificationCreditPackageResponse>>> listPackages() {
        List<NotificationCreditPackageResponse> response = creditService.listPackages();
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 通知クレジット購入用 Checkout Session を作成する（ADMINのみ）。
     *
     * @param orgId   組織ID
     * @param request 購入リクエスト（packageId を含む）
     * @return Checkout セッション情報
     */
    @PostMapping("/api/v1/organizations/{orgId}/notification-credits/checkout")
    @Operation(summary = "通知クレジット購入Checkout作成")
    public ResponseEntity<ApiResponse<NotificationCreditCheckoutResponse>> createCheckout(
            @PathVariable Long orgId,
            @Valid @RequestBody NotificationCreditCheckoutRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (!accessControlService.isAdmin(currentUserId, orgId, "ORGANIZATION")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        NotificationCreditCheckoutResponse response =
                checkoutService.createCheckout(orgId, request.packageId(), currentUserId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
