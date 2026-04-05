package com.mannschaft.app.facility.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.facility.dto.FacilitySettingsResponse;
import com.mannschaft.app.facility.dto.FacilityStatsResponse;
import com.mannschaft.app.facility.dto.UpdateSettingsRequest;
import com.mannschaft.app.facility.service.FacilitySettingsService;
import com.mannschaft.app.facility.service.FacilityStatsService;
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
 * 組織施設設定・統計コントローラー。
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/facilities")
@Tag(name = "組織施設設定", description = "F09.5 組織施設設定・統計")
@RequiredArgsConstructor
public class OrgFacilitySettingsController {

    private static final String SCOPE_TYPE = "ORGANIZATION";

    private final FacilitySettingsService settingsService;
    private final FacilityStatsService statsService;

    @GetMapping("/settings")
    @Operation(summary = "施設予約設定取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<FacilitySettingsResponse>> getSettings(
            @PathVariable Long organizationId) {
        FacilitySettingsResponse response = settingsService.getSettings(SCOPE_TYPE, organizationId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PutMapping("/settings")
    @Operation(summary = "施設予約設定更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<FacilitySettingsResponse>> updateSettings(
            @PathVariable Long organizationId,
            @Valid @RequestBody UpdateSettingsRequest request) {
        FacilitySettingsResponse response = settingsService.updateSettings(SCOPE_TYPE, organizationId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @GetMapping("/stats")
    @Operation(summary = "施設統計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<FacilityStatsResponse>> getStats(
            @PathVariable Long organizationId) {
        FacilityStatsResponse response = statsService.getStats(SCOPE_TYPE, organizationId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
