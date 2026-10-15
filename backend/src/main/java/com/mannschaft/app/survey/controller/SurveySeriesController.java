package com.mannschaft.app.survey.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.survey.dto.SurveyComparisonResponse;
import com.mannschaft.app.survey.service.SurveySeriesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * アンケートシリーズコントローラー。F05.4 §4.9 シリーズ比較 API を提供する。
 */
@RestController
@RequestMapping("/api/v1/surveys/series")
@Tag(name = "アンケートシリーズ比較", description = "F05.4 §4.9 同一 series_id のアンケート時系列比較")
@RequiredArgsConstructor
public class SurveySeriesController {

    private final SurveySeriesService seriesService;

    /**
     * F05.4 §4.9 シリーズ時系列比較。
     *
     * <p>同一 {@code series_id} のアンケートを時系列で比較し、設問別トレンドを返す。
     * ADMIN+ のみ利用可能。</p>
     */
    @GetMapping("/{seriesId}/comparison")
    @Operation(summary = "アンケートシリーズ時系列比較",
            description = "F05.4 §4.9 同一 series_id のアンケートを時系列で比較する")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<SurveyComparisonResponse>> compareSeries(
            @PathVariable String seriesId) {
        SurveyComparisonResponse response = seriesService.compareSeries(
                seriesId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
