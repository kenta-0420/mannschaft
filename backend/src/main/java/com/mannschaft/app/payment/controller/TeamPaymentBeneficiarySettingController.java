package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.dto.PaymentBeneficiarySettingResponse;
import com.mannschaft.app.payment.dto.UpdatePaymentBeneficiarySettingRequest;
import com.mannschaft.app.payment.service.PaymentBeneficiarySettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * チームの会費受益者制限設定コントローラー（F08.9）。
 *
 * <p>「受益者は会員(MEMBER)のみ」設定の取得・更新 API を提供する。更新は ADMIN 必須。
 * 既定は ON（会員のみ・純 SUPPORTER 除外）。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{id}/payment-beneficiary-setting")
@Tag(name = "チーム会費受益者制限設定", description = "F08.9 会費受益者を会員のみに限定する設定")
@RequiredArgsConstructor
public class TeamPaymentBeneficiarySettingController {

    private final PaymentBeneficiarySettingService settingService;
    private final AccessControlService accessControlService;

    /**
     * チームの会費受益者制限設定を取得する（既定は会員のみ＝true）。
     */
    @GetMapping
    @Operation(summary = "会費受益者制限設定の取得")
    public ResponseEntity<ApiResponse<PaymentBeneficiarySettingResponse>> getSetting(@PathVariable Long id) {
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, "TEAM");
        boolean memberOnly = settingService.isMemberOnly(id, null);
        return ResponseEntity.ok(ApiResponse.of(new PaymentBeneficiarySettingResponse(memberOnly)));
    }

    /**
     * チームの会費受益者制限設定を更新する（ADMIN 必須）。
     */
    @PutMapping
    @Operation(summary = "会費受益者制限設定の更新")
    public ResponseEntity<ApiResponse<PaymentBeneficiarySettingResponse>> updateSetting(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePaymentBeneficiarySettingRequest request) {
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, "TEAM");
        var saved = settingService.updateSetting(id, null, request.beneficiaryMemberOnly());
        return ResponseEntity.ok(ApiResponse.of(
                new PaymentBeneficiarySettingResponse(Boolean.TRUE.equals(saved.getBeneficiaryMemberOnly()))));
    }
}
