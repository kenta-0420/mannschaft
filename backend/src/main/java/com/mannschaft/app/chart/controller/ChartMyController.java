package com.mannschaft.app.chart.controller;

import com.mannschaft.app.chart.dto.ChartRecordSummaryResponse;
import com.mannschaft.app.chart.service.ChartRecordService;
import com.mannschaft.app.common.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * マイカルテコントローラー。自分に共有されたカルテを全チーム横断で取得する。
 */
@RestController
@RequestMapping("/api/v1/charts")
@Tag(name = "マイカルテ", description = "F07.4 自分のカルテ（共有されたもの）")
@RequiredArgsConstructor
public class ChartMyController {

    private final ChartRecordService chartRecordService;


    /**
     * 19. 自分のカルテ（共有されたもの）
     * GET /api/v1/charts/me
     */
    @GetMapping("/me")
    @Operation(summary = "マイカルテ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<ChartRecordSummaryResponse>> listMyCharts(
            @RequestParam(required = false) Long teamId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ChartRecordSummaryResponse> result = chartRecordService.listMyCharts(
                SecurityUtils.getCurrentUserId(), teamId, PageRequest.of(page, Math.min(size, 100)));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }
}
