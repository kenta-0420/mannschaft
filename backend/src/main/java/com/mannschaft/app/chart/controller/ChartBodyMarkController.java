package com.mannschaft.app.chart.controller;

import com.mannschaft.app.chart.dto.BodyMarksResponse;
import com.mannschaft.app.chart.dto.UpdateBodyMarksRequest;
import com.mannschaft.app.chart.service.ChartBodyMarkService;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 身体チャートコントローラー。身体チャートマークの一括更新APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/charts/{id}/body-marks")
@Tag(name = "身体チャート", description = "F07.4 身体チャートマーク一括更新")
@RequiredArgsConstructor
public class ChartBodyMarkController {

    private final ChartBodyMarkService bodyMarkService;

    /**
     * 10. 身体チャート一括更新
     * PUT /api/v1/teams/{teamId}/charts/{id}/body-marks
     */
    @PutMapping
    @Operation(summary = "身体チャート一括更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<BodyMarksResponse>> updateBodyMarks(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateBodyMarksRequest request) {
        BodyMarksResponse response = bodyMarkService.updateBodyMarks(teamId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
