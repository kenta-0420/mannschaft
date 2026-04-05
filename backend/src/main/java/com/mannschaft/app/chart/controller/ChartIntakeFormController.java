package com.mannschaft.app.chart.controller;

import com.mannschaft.app.chart.dto.IntakeFormResponse;
import com.mannschaft.app.chart.dto.UpdateIntakeFormRequest;
import com.mannschaft.app.chart.service.ChartIntakeFormService;
import com.mannschaft.app.common.ApiResponse;
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

import java.util.List;

/**
 * 問診票コントローラー。問診票の取得・更新APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/charts/{id}/intake-form")
@Tag(name = "問診票", description = "F07.4 問診票取得・更新")
@RequiredArgsConstructor
public class ChartIntakeFormController {

    private final ChartIntakeFormService intakeFormService;

    /**
     * 8. 問診票取得
     * GET /api/v1/teams/{teamId}/charts/{id}/intake-form
     */
    @GetMapping
    @Operation(summary = "問診票取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<IntakeFormResponse>>> getIntakeForms(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        List<IntakeFormResponse> response = intakeFormService.getIntakeForms(teamId, id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 9. 問診票更新
     * PUT /api/v1/teams/{teamId}/charts/{id}/intake-form
     */
    @PutMapping
    @Operation(summary = "問診票更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<IntakeFormResponse>> updateIntakeForm(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateIntakeFormRequest request) {
        IntakeFormResponse response = intakeFormService.updateIntakeForm(teamId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
