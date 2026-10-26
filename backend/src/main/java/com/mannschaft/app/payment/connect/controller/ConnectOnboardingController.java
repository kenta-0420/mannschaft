package com.mannschaft.app.payment.connect.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.payment.connect.ConnectAccountService;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.connect.dto.ConnectStatusResponse;
import com.mannschaft.app.payment.connect.dto.OnboardingLinkRequest;
import com.mannschaft.app.payment.connect.dto.OnboardingLinkResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F22.1 謝礼決済: Connect onboarding コントローラー（設計書 02 §2）。
 *
 * <p>認証必須（{@code .authenticated()} がカバー・03 §2.1）。認可（USER 本人固定 / TEAM/ORG scope ADMIN）と
 * IDOR（scope 所有権照合・404 秘匿）はサービス層 {@link ConnectAccountService} で担保する。</p>
 *
 * <p>エンドポイント数: 2（POST onboarding-link / GET status）。</p>
 */
@RestController
@RequestMapping("/api/v1/payment/connect")
@Tag(name = "謝礼決済 Connect", description = "F22.1 Connect onboarding / 状態照会")
@RequiredArgsConstructor
public class ConnectOnboardingController {

    private final ConnectAccountService connectAccountService;

    /**
     * Connect onboarding リンクを発行する（設計書 02 §2.1）。
     */
    @PostMapping("/onboarding-link")
    @Operation(summary = "Connect onboarding リンク発行（個人/チーム/組織）")
    public ResponseEntity<ApiResponse<OnboardingLinkResponse>> createOnboardingLink(
            @Valid @RequestBody OnboardingLinkRequest request) {
        OnboardingLinkResponse response = connectAccountService.createOnboardingLink(request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * Connect 状態を取得する（設計書 02 §2.2）。
     *
     * <p>{@code scopeId} は USER 時は無視され本人へ固定される（サービス層で処理）。</p>
     */
    @GetMapping("/status")
    @Operation(summary = "Connect 状態照会")
    public ResponseEntity<ApiResponse<ConnectStatusResponse>> getStatus(
            @RequestParam ScopeKind scopeKind,
            @RequestParam(required = false) Long scopeId) {
        ConnectStatusResponse response = connectAccountService.getStatus(scopeKind, scopeId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
