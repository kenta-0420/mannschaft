package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.dto.PaymentSummaryResponse;
import com.mannschaft.app.payment.service.PaymentSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * チーム支払いサマリーコントローラー。
 * <p>
 * エンドポイント数: 1（GET payment-summary）
 * </p>
 *
 * <p><b>認可根治戦役 Wave3-B1b（2026-07-16）:</b> 会費総額/未払い/期限切れ件数の集計は非会員に
 * 見せるべきでない財務情報のため、{@link AccessControlService#checkMembership}（"TEAM" scope）
 * で保護する。双子の {@link OrganizationPaymentSummaryController} も同一戦役で同水準の認可を敷設した。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{id}")
@Tag(name = "チーム支払いサマリー", description = "F08.2 チーム支払いサマリー")
@RequiredArgsConstructor
public class TeamPaymentSummaryController {

    private final PaymentSummaryService paymentSummaryService;
    private final AccessControlService accessControlService;

    /**
     * チーム支払いサマリーを取得する。
     */
    @GetMapping("/payment-summary")
    @Operation(summary = "チーム支払いサマリー")
    public ResponseEntity<ApiResponse<PaymentSummaryResponse>> getPaymentSummary(
            @PathVariable Long id) {
        accessControlService.checkMembership(SecurityUtils.getCurrentUserId(), id, "TEAM");
        PaymentSummaryResponse response = paymentSummaryService.getTeamPaymentSummary(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
