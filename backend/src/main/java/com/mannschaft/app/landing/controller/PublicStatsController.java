package com.mannschaft.app.landing.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.landing.dto.PublicStatsResponse;
import com.mannschaft.app.landing.service.PublicStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ランディングページ公開統計コントローラー。認証不要エンドポイント。
 */
@RestController
@RequestMapping("/api/v1/public")
@Tag(name = "公開統計", description = "ランディングページ用公開統計API")
@RequiredArgsConstructor
public class PublicStatsController {

    private final PublicStatsService publicStatsService;

    @GetMapping("/stats")
    @Operation(summary = "公開統計取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<PublicStatsResponse>> getPublicStats() {
        PublicStatsResponse stats = publicStatsService.getPublicStats();
        return ResponseEntity.ok(ApiResponse.of(stats));
    }
}
