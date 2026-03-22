package com.mannschaft.app.parking.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.parking.dto.StripeConnectStatusResponse;
import com.mannschaft.app.parking.service.StripeConnectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Stripe Connect コントローラー（2 EP）。
 */
@RestController
@RequestMapping("/api/v1/users/me/stripe-connect")
@Tag(name = "Stripe Connect", description = "F09.3 Stripe Connect オンボーディング・ステータス")
@RequiredArgsConstructor
public class StripeConnectController {

    private final StripeConnectService stripeConnectService;

    private Long getCurrentUserId() {
        return 1L;
    }

    @PostMapping("/onboarding")
    @Operation(summary = "Stripe Connect オンボーディング開始")
    public ResponseEntity<ApiResponse<Map<String, String>>> startOnboarding() {
        String url = stripeConnectService.startOnboarding(getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(Map.of("onboardingUrl", url)));
    }

    @GetMapping("/status")
    @Operation(summary = "Stripe Connect ステータス取得")
    public ResponseEntity<ApiResponse<StripeConnectStatusResponse>> getStatus() {
        StripeConnectStatusResponse result = stripeConnectService.getStatus(getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
