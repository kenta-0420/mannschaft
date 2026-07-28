package com.mannschaft.app.digest.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
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
 *
 * <p><b>認可根拠（{@link AuthorizedByPathConfig} クラス付与・凍結ストア該当 1 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは、{@code SecurityConfig} のパス単位認可により
 * SYSTEM_ADMIN ロール保持者のみへ宣言的に予約されている。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig.java:419 — requestMatchers("/api/v1/system-admin/**").hasRole("SYSTEM_ADMIN")
 * </p>
 *
 * <p>Controller / Service 側に認可コードは存在しないが、フィルタチェーンで強制されるため
 * 無認可ではない。認可根治戦役 Wave5 監査済。パス定義を変更・削除する際は本注釈の根拠が
 * 失効するため、必ず併せて見直すこと。</p>
 */
@AuthorizedByPathConfig
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
