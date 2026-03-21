package com.mannschaft.app.forms.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.forms.dto.CreateFormPresetRequest;
import com.mannschaft.app.forms.dto.FormPresetResponse;
import com.mannschaft.app.forms.dto.UpdateFormPresetRequest;
import com.mannschaft.app.forms.service.FormPresetService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * フォームプリセットコントローラー。SYSTEM_ADMIN向けプリセット管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/admin/form-presets")
@Tag(name = "フォームプリセット", description = "F05.7 システム管理者向けプリセット管理")
@RequiredArgsConstructor
public class FormPresetController {

    private final FormPresetService presetService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * プリセット一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "プリセット一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<FormPresetResponse>>> listPresets(
            @RequestParam(required = false) String category) {
        List<FormPresetResponse> presets = presetService.listPresets(category);
        return ResponseEntity.ok(ApiResponse.of(presets));
    }

    /**
     * プリセット詳細を取得する。
     */
    @GetMapping("/{presetId}")
    @Operation(summary = "プリセット詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<FormPresetResponse>> getPreset(
            @PathVariable Long presetId) {
        FormPresetResponse response = presetService.getPreset(presetId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * プリセットを作成する。
     */
    @PostMapping
    @Operation(summary = "プリセット作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<FormPresetResponse>> createPreset(
            @Valid @RequestBody CreateFormPresetRequest request) {
        FormPresetResponse response = presetService.createPreset(getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * プリセットを更新する。
     */
    @PutMapping("/{presetId}")
    @Operation(summary = "プリセット更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<FormPresetResponse>> updatePreset(
            @PathVariable Long presetId,
            @Valid @RequestBody UpdateFormPresetRequest request) {
        FormPresetResponse response = presetService.updatePreset(presetId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * プリセットを削除する。
     */
    @DeleteMapping("/{presetId}")
    @Operation(summary = "プリセット削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deletePreset(@PathVariable Long presetId) {
        presetService.deletePreset(presetId);
        return ResponseEntity.noContent().build();
    }
}
