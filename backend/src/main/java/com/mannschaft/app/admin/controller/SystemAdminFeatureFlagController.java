package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.FeatureFlagResponse;
import com.mannschaft.app.admin.dto.UpdateFeatureFlagRequest;
import com.mannschaft.app.admin.service.FeatureFlagService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
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
import com.mannschaft.app.common.SecurityUtils;

/**
 * システム管理者向けフィーチャーフラグコントローラー。
 *
 * <p><b>認可根拠（{@link AuthorizedByPathConfig} クラス付与・凍結ストア該当 2 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは、{@code SecurityConfig} のパス単位認可により
 * SYSTEM_ADMIN ロール保持者のみへ宣言的に予約されている。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig の requestMatchers("/api/v1/system-admin/**").hasRole("SYSTEM_ADMIN")
 * </p>
 *
 * <p>Controller / Service 側に認可コードは存在しないが、フィルタチェーンで強制されるため
 * 無認可ではない。認可根治戦役 Wave5 監査済。パス定義を変更・削除する際は本注釈の根拠が
 * 失効するため、必ず併せて見直すこと。</p>
 */
@AuthorizedByPathConfig("/api/v1/system-admin/**")
@RestController
@RequestMapping("/api/v1/system-admin/feature-flags")
@Tag(name = "システム管理 - フィーチャーフラグ", description = "F10.1 フィーチャーフラグ管理API")
@RequiredArgsConstructor
public class SystemAdminFeatureFlagController {

    private final FeatureFlagService featureFlagService;


    /**
     * 全フィーチャーフラグ一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "フィーチャーフラグ一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<FeatureFlagResponse>>> getAllFlags() {
        List<FeatureFlagResponse> flags = featureFlagService.getAllFlags();
        return ResponseEntity.ok(ApiResponse.of(flags));
    }

    /**
     * フィーチャーフラグを更新する。
     */
    @PutMapping("/{flagKey}")
    @Operation(summary = "フィーチャーフラグ更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<FeatureFlagResponse>> updateFlag(
            @PathVariable String flagKey,
            @Valid @RequestBody UpdateFeatureFlagRequest request) {
        FeatureFlagResponse response = featureFlagService.updateFlag(flagKey, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
