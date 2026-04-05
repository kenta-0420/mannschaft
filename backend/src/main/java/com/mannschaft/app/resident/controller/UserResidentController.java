package com.mannschaft.app.resident.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.resident.dto.DwellingUnitResponse;
import com.mannschaft.app.resident.dto.ResidentResponse;
import com.mannschaft.app.resident.service.ResidentRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 個人用居住者情報コントローラー。
 */
@RestController
@Tag(name = "個人用居住者情報", description = "F09.1 個人用居室・居住者情報")
@RequiredArgsConstructor
public class UserResidentController {

    private final ResidentRegistryService residentService;

    @GetMapping("/api/v1/users/me/dwelling-unit")
    @Operation(summary = "自室情報")
    public ResponseEntity<ApiResponse<DwellingUnitResponse>> getMyUnit() {
        return ResponseEntity.ok(ApiResponse.of(residentService.getMyUnit(SecurityUtils.getCurrentUserId())));
    }

    @GetMapping("/api/v1/users/me/resident-info")
    @Operation(summary = "自身の居住者情報")
    public ResponseEntity<ApiResponse<ResidentResponse>> getMyResidentInfo() {
        return ResponseEntity.ok(ApiResponse.of(residentService.getMyResidentInfo(SecurityUtils.getCurrentUserId())));
    }
}
