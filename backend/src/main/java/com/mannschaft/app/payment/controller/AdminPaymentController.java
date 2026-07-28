package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.payment.dto.ReconcileResponse;
import com.mannschaft.app.payment.service.MemberPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SYSTEM_ADMIN 専用支払い管理コントローラー。Stripe 手動再同期を提供する。
 * <p>
 * エンドポイント数: 1（POST admin/stripe/reconcile/{paymentId}）
 *
 * <p><b>認可根拠（{@link AuthorizedByPathConfig} クラス付与・凍結ストア該当 1 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは、{@code SecurityConfig} のパス単位認可により
 * SYSTEM_ADMIN ロール保持者のみへ宣言的に予約されている。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig.java:369 — requestMatchers("/api/v1/admin/stripe/**").hasRole("SYSTEM_ADMIN")
 * </p>
 *
 * <p>Controller / Service 側に認可コードは存在しないが、フィルタチェーンで強制されるため
 * 無認可ではない。認可根治戦役 Wave5 監査済。パス定義を変更・削除する際は本注釈の根拠が
 * 失効するため、必ず併せて見直すこと。</p>
 */
@AuthorizedByPathConfig
@RestController
@RequestMapping("/api/v1/admin/stripe")
@Tag(name = "管理者支払い操作", description = "F08.2 SYSTEM_ADMIN 専用支払い管理")
@RequiredArgsConstructor
public class AdminPaymentController {

    private final MemberPaymentService memberPaymentService;

    /**
     * Stripe 状態との手動再同期を実行する。
     */
    @PostMapping("/reconcile/{paymentId}")
    @Operation(summary = "Stripe 手動再同期")
    public ResponseEntity<ApiResponse<ReconcileResponse>> reconcile(
            @PathVariable Long paymentId) {
        ReconcileResponse response = memberPaymentService.reconcile(paymentId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
