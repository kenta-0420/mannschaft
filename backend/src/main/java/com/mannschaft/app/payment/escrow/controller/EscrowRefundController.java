package com.mannschaft.app.payment.escrow.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.dto.RefundRequest;
import com.mannschaft.app.payment.escrow.dto.RefundResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F22.1 謝礼決済 P2-c 第二波: エスクロー返金コントローラー（設計書 02 §6.1・設定A）。
 *
 * <p>認証必須（{@code .authenticated()} がカバー・03 §2.1）。認可（受取側 scope の ADMIN）と IDOR
 * （payee scope 所有権照合・無関係 scope は 404 秘匿）はサービス層 {@link ConnectChargeService#refund} で
 * 担保する。<b>Mannschaft 運営は返金操作に関与しない</b>（受取側 ADMIN が操作・設定A）。</p>
 *
 * <p>capture 後は Stripe Refund（{@code feeBearer} で 2モード分岐・03 §6.1）、capture 前は与信取消
 * （支払者課金なし）。レスポンスに PCI 機密（client_secret/pi_/acct_）は載せない（03 §10）。</p>
 *
 * <p>エンドポイント数: 1（POST refund）。</p>
 */
@RestController
@RequestMapping("/api/v1/payment/escrow")
@Tag(name = "謝礼決済 返金", description = "F22.1 エスクロー返金 / 与信取消（受取側 ADMIN・設定A）")
@RequiredArgsConstructor
public class EscrowRefundController {

    private final ConnectChargeService connectChargeService;

    /**
     * 返金（または capture 前の与信取消）を行う（設計書 02 §6.1）。
     *
     * @param id      エスクロー取引 ID
     * @param request 返金リクエスト（amount 任意=全額/一部・reason）
     * @return 返金結果（返金後の状態・額面ベースの返金額/残額）
     */
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "既存エスクロー取引の返金義務をGate状態にかかわらず履行するため")
    @PostMapping("/{id}/refund")
    @Operation(summary = "返金 / 与信取消（受取側 ADMIN・feeBearer=PAYER|PAYEE の 2モード）")
    public ResponseEntity<ApiResponse<RefundResponse>> refund(
            @PathVariable UUID id,
            @Valid @RequestBody RefundRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        ConnectChargeService.RefundResult result = connectChargeService.refund(
                id, request.amount(), request.feeBearer(), request.reason(), request.reasonDetail(), actorUserId);
        RefundResponse response = new RefundResponse(
                result.escrowId(), result.status(), result.refundedAmount(), result.residualAmount());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
