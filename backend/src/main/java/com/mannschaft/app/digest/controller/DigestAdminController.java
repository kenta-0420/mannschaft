package com.mannschaft.app.digest.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.digest.dto.DigestUsageResponse;
import com.mannschaft.app.digest.service.DigestGenerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * タイムラインダイジェスト SYSTEM_ADMIN コントローラー。
 * AI API 利用量統計エンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/system-admin/timeline-digest")
@Tag(name = "タイムラインダイジェスト管理")
@RequiredArgsConstructor
public class DigestAdminController {

    private final DigestGenerationService digestGenerationService;

    /**
     * AI API 利用量統計。
     */
    @GetMapping("/usage")
    @Operation(summary = "AI API 利用量統計")
    public ResponseEntity<ApiResponse<DigestUsageResponse>> getUsage(
            @RequestParam(required = false) String period) {
        DigestUsageResponse response = digestGenerationService.getUsage(period);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
