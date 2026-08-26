package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.tournament.dto.CreatePresetRequest;
import com.mannschaft.app.tournament.dto.PresetResponse;
import com.mannschaft.app.tournament.dto.UpdatePresetRequest;
import com.mannschaft.app.tournament.service.SystemPresetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SYSTEM_ADMIN向けプリセット管理コントローラー。
 * 5 endpoints: GET list, POST create, GET detail, PATCH update, DELETE
 *
 * <p><b>認可根拠（{@link AuthorizedByPathConfig} クラス付与・凍結ストア該当 5 EP）</b>:
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
@RequestMapping("/api/v1/system-admin/tournament-presets")
@Tag(name = "大会プリセット管理（SYSTEM_ADMIN）", description = "F08.7 プリセットCRUD")
@RequiredArgsConstructor
public class SystemPresetController {

    private final SystemPresetService presetService;

    @GetMapping
    @Operation(summary = "プリセット一覧")
    public ResponseEntity<PagedResponse<PresetResponse>> listPresets(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PresetResponse> result = presetService.listPresets(PageRequest.of(page, size));
        return ResponseEntity.ok(PagedResponse.of(result.getContent(),
                new PagedResponse.PageMeta(result.getTotalElements(), page, size, result.getTotalPages())));
    }

    @PostMapping
    @Operation(summary = "プリセット作成")
    public ResponseEntity<ApiResponse<PresetResponse>> createPreset(
            @Valid @RequestBody CreatePresetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(presetService.createPreset(request)));
    }

    @GetMapping("/{presetId}")
    @Operation(summary = "プリセット詳細")
    public ResponseEntity<ApiResponse<PresetResponse>> getPreset(@PathVariable Long presetId) {
        return ResponseEntity.ok(ApiResponse.of(presetService.getPreset(presetId)));
    }

    @PatchMapping("/{presetId}")
    @Operation(summary = "プリセット更新")
    public ResponseEntity<ApiResponse<PresetResponse>> updatePreset(
            @PathVariable Long presetId,
            @Valid @RequestBody UpdatePresetRequest request) {
        return ResponseEntity.ok(ApiResponse.of(presetService.updatePreset(presetId, request)));
    }

    @DeleteMapping("/{presetId}")
    @Operation(summary = "プリセット論理削除")
    public ResponseEntity<Void> deletePreset(@PathVariable Long presetId) {
        presetService.deletePreset(presetId);
        return ResponseEntity.noContent().build();
    }
}
