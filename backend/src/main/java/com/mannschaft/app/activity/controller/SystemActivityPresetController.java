package com.mannschaft.app.activity.controller;

import com.mannschaft.app.activity.dto.CreatePresetRequest;
import com.mannschaft.app.activity.dto.PresetResponse;
import com.mannschaft.app.activity.dto.UpdatePresetRequest;
import com.mannschaft.app.activity.service.SystemActivityPresetService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SYSTEM_ADMIN用プリセットテンプレートCRUDコントローラー。
 *
 * <p><b>認可根拠（{@link AuthorizedByPathConfig} クラス付与・凍結ストア該当 4 EP）</b>:
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
@RequestMapping("/api/v1/system-admin/activity-template-presets")
@Tag(name = "プリセットテンプレート管理", description = "F06.4 SYSTEM_ADMIN用プリセットテンプレートCRUD")
@RequiredArgsConstructor
public class SystemActivityPresetController {

    private final SystemActivityPresetService presetService;

    /**
     * プリセット一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "プリセット一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PresetResponse>>> listPresets() {
        return ResponseEntity.ok(ApiResponse.of(presetService.listPresets()));
    }

    /**
     * プリセットを作成する。
     */
    @PostMapping
    @Operation(summary = "プリセット作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<PresetResponse>> createPreset(
            @Valid @RequestBody CreatePresetRequest request) {
        PresetResponse response = presetService.createPreset(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * プリセットを更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "プリセット更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<PresetResponse>> updatePreset(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePresetRequest request) {
        return ResponseEntity.ok(ApiResponse.of(presetService.updatePreset(id, request)));
    }

    /**
     * プリセットを削除する。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "プリセット削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deletePreset(@PathVariable Long id) {
        presetService.deletePreset(id);
        return ResponseEntity.noContent().build();
    }
}
