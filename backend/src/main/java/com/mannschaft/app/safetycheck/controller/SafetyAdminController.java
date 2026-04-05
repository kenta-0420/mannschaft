package com.mannschaft.app.safetycheck.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.safetycheck.dto.CreatePresetRequest;
import com.mannschaft.app.safetycheck.dto.CreateTemplateRequest;
import com.mannschaft.app.safetycheck.dto.SafetyPresetResponse;
import com.mannschaft.app.safetycheck.dto.SafetyTemplateResponse;
import com.mannschaft.app.safetycheck.dto.UpdatePresetRequest;
import com.mannschaft.app.safetycheck.dto.UpdateTemplateRequest;
import com.mannschaft.app.safetycheck.service.SafetyPresetService;
import com.mannschaft.app.safetycheck.service.SafetyTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 安否確認管理者コントローラー。SYSTEM_ADMIN向けのプリセット・テンプレートCRUD APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/system-admin/safety-checks")
@Tag(name = "安否確認管理者", description = "F03.6 SYSTEM_ADMIN向け安否確認管理")
@RequiredArgsConstructor
public class SafetyAdminController {

    private final SafetyPresetService presetService;
    private final SafetyTemplateService templateService;


    // --- プリセット管理 ---

    /**
     * プリセット一覧を取得する（管理者用・全件）。
     */
    @GetMapping("/presets")
    @Operation(summary = "プリセット一覧（管理者用）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<SafetyPresetResponse>>> listPresets() {
        List<SafetyPresetResponse> presets = presetService.listAllPresets();
        return ResponseEntity.ok(ApiResponse.of(presets));
    }

    /**
     * プリセットを作成する。
     */
    @PostMapping("/presets")
    @Operation(summary = "プリセット作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<SafetyPresetResponse>> createPreset(
            @Valid @RequestBody CreatePresetRequest request) {
        SafetyPresetResponse response = presetService.createPreset(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * プリセットを更新する。
     */
    @PatchMapping("/presets/{presetId}")
    @Operation(summary = "プリセット更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<SafetyPresetResponse>> updatePreset(
            @PathVariable Long presetId,
            @Valid @RequestBody UpdatePresetRequest request) {
        SafetyPresetResponse response = presetService.updatePreset(presetId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * プリセットを削除する。
     */
    @DeleteMapping("/presets/{presetId}")
    @Operation(summary = "プリセット削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deletePreset(@PathVariable Long presetId) {
        presetService.deletePreset(presetId);
        return ResponseEntity.noContent().build();
    }

    // --- テンプレート管理 ---

    /**
     * テンプレート一覧を取得する（管理者用・全件）。
     */
    @GetMapping("/templates")
    @Operation(summary = "テンプレート一覧（管理者用）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<SafetyTemplateResponse>>> listTemplates() {
        List<SafetyTemplateResponse> templates = templateService.listAllTemplates();
        return ResponseEntity.ok(ApiResponse.of(templates));
    }

    /**
     * テンプレートを作成する（管理者用）。
     */
    @PostMapping("/templates")
    @Operation(summary = "テンプレート作成（管理者用）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<SafetyTemplateResponse>> createTemplate(
            @Valid @RequestBody CreateTemplateRequest request) {
        SafetyTemplateResponse response = templateService.createTemplate(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * テンプレートを更新する（管理者用）。
     */
    @PatchMapping("/templates/{templateId}")
    @Operation(summary = "テンプレート更新（管理者用）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<SafetyTemplateResponse>> updateTemplate(
            @PathVariable Long templateId,
            @Valid @RequestBody UpdateTemplateRequest request) {
        SafetyTemplateResponse response = templateService.updateTemplate(templateId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * テンプレートを削除する（管理者用）。
     */
    @DeleteMapping("/templates/{templateId}")
    @Operation(summary = "テンプレート削除（管理者用）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteTemplate(@PathVariable Long templateId) {
        templateService.deleteTemplate(templateId);
        return ResponseEntity.noContent().build();
    }
}
