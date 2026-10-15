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
 * 組織の会費受益者制限設定コントローラー（F08.9）。
 *
 * <p>「受益者は会員(MEMBER)のみ」設定の取得・更新 API を提供する。更新は ADMIN 必須。
 * 既定は ON（会員のみ・純 SUPPORTER 除外）。組織配下チームの会員は受益者可。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{id}/payment-beneficiary-setting")
@Tag(name = "組織会費受益者制限設定", description = "F08.9 会費受益者を会員のみに限定する設定")
@RequiredArgsConstructor
public class OrganizationPaymentBeneficiarySettingController {

    private final PaymentBeneficiarySettingService settingService;
    private final AccessControlService accessControlService;

    /**
     * 組織の会費受益者制限設定を取得する（既定は会員のみ＝true）。
     */
    @GetMapping
    @Operation(summary = "会費受益者制限設定の取得")
    public ResponseEntity<ApiResponse<PaymentBeneficiarySettingResponse>> getSetting(@PathVariable Long id) {
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, "ORGANIZATION");
        boolean memberOnly = settingService.isMemberOnly(null, id);
        return ResponseEntity.ok(ApiResponse.of(new PaymentBeneficiarySettingResponse(memberOnly)));
    }

    /**
     * 組織の会費受益者制限設定を更新する（ADMIN 必須）。
     */
    @PutMapping
    @Operation(summary = "会費受益者制限設定の更新")
    public ResponseEntity<ApiResponse<PaymentBeneficiarySettingResponse>> updateSetting(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePaymentBeneficiarySettingRequest request) {
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, "ORGANIZATION");
        var saved = settingService.updateSetting(null, id, request.beneficiaryMemberOnly());
        return ResponseEntity.ok(ApiResponse.of(
                new PaymentBeneficiarySettingResponse(Boolean.TRUE.equals(saved.getBeneficiaryMemberOnly()))));
    }
}
