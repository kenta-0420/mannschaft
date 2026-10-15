package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.BetaRestrictionConfigResponse;
import com.mannschaft.app.admin.dto.UpdateBetaRestrictionRequest;
import com.mannschaft.app.admin.service.BetaRestrictionService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * システム管理者向けベータ登録制限コントローラー。
 * SYSTEM_ADMIN のみアクセス可能。
 */
@RestController
@RequestMapping("/api/v1/system-admin/beta-restriction")
@Tag(name = "システム管理 - ベータ登録制限", description = "F00.6 ベータ登録制限管理API")
@RequiredArgsConstructor
public class SystemAdminBetaRestrictionController {

    private final BetaRestrictionService betaRestrictionService;
    private final AccessControlService accessControlService;

    /**
     * ベータ登録制限設定を取得する。
     */
    @GetMapping
    @Operation(summary = "ベータ登録制限設定取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<BetaRestrictionConfigResponse>> getConfig() {
        accessControlService.checkSystemAdmin(SecurityUtils.getCurrentUserId());
        BetaRestrictionConfigResponse response = betaRestrictionService.getConfig();
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ベータ登録制限設定を更新する。
     */
    @PutMapping
    @Operation(summary = "ベータ登録制限設定更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<BetaRestrictionConfigResponse>> updateConfig(
            @Valid @RequestBody UpdateBetaRestrictionRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkSystemAdmin(userId);
        betaRestrictionService.updateConfig(request, userId);
        BetaRestrictionConfigResponse response = betaRestrictionService.getConfig();
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
