package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * サブスクリプションコントローラー（Phase 4 予定）。
 * <p>
 * エンドポイント数: 2（DELETE subscriptions/{subscriptionId}, PATCH resume）
 */
@RestController
@RequestMapping("/api/v1/payment-items/{itemId}/subscriptions")
@Tag(name = "サブスクリプション", description = "F08.2 サブスクリプション管理（Phase 4）")
@RequiredArgsConstructor
public class SubscriptionController {

    /**
     * サブスクリプション期末解約を実行する（Phase 4）。
     */
    @DeleteMapping("/{subscriptionId}")
    @Operation(summary = "サブスクリプション期末解約（Phase 4）")
    public ResponseEntity<ApiResponse<Map<String, String>>> cancelSubscription(
            @PathVariable Long itemId,
            @PathVariable Long subscriptionId) {
        // Phase 4 実装予定
        return ResponseEntity.ok(ApiResponse.of(Map.of("message", "Phase 4 で実装予定")));
    }

    /**
     * 期末解約の取り消しを実行する（Phase 4）。
     */
    @PatchMapping("/{subscriptionId}/resume")
    @Operation(summary = "期末解約取り消し（Phase 4）")
    public ResponseEntity<ApiResponse<Map<String, String>>> resumeSubscription(
            @PathVariable Long itemId,
            @PathVariable Long subscriptionId) {
        // Phase 4 実装予定
        return ResponseEntity.ok(ApiResponse.of(Map.of("message", "Phase 4 で実装予定")));
    }
}
