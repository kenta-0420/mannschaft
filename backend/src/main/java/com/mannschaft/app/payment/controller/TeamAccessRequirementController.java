package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.payment.dto.AccessRequirementsRequest;
import com.mannschaft.app.payment.dto.AccessRequirementsResponse;
import com.mannschaft.app.payment.service.AccessRequirementService;
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
 * チームアクセス要件コントローラー。チーム全体ロック設定の GET/PUT を提供する。
 * <p>
 * エンドポイント数: 2（GET, PUT）
 */
@RestController
@RequestMapping("/api/v1/teams/{id}/access-requirements")
@Tag(name = "チームアクセス要件", description = "F08.2 チーム全体ロック設定")
@RequiredArgsConstructor
public class TeamAccessRequirementController {

    private final AccessRequirementService accessRequirementService;

    /**
     * チーム全体ロック設定を取得する。
     */
    @GetMapping
    @Operation(summary = "チームアクセス要件取得")
    public ResponseEntity<ApiResponse<AccessRequirementsResponse>> getAccessRequirements(
            @PathVariable Long id) {
        AccessRequirementsResponse response = accessRequirementService.getTeamAccessRequirements(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チーム全体ロック設定を一括設定する。
     */
    @PutMapping
    @Operation(summary = "チームアクセス要件設定")
    public ResponseEntity<ApiResponse<AccessRequirementsResponse>> setAccessRequirements(
            @PathVariable Long id,
            @Valid @RequestBody AccessRequirementsRequest request) {
        AccessRequirementsResponse response = accessRequirementService.setTeamAccessRequirements(id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
