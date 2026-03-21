package com.mannschaft.app.chart.controller;

import com.mannschaft.app.chart.dto.CreateCustomFieldRequest;
import com.mannschaft.app.chart.dto.CreateRecordTemplateRequest;
import com.mannschaft.app.chart.dto.CustomFieldResponse;
import com.mannschaft.app.chart.dto.RecordTemplateResponse;
import com.mannschaft.app.chart.dto.SectionSettingResponse;
import com.mannschaft.app.chart.dto.UpdateCustomFieldRequest;
import com.mannschaft.app.chart.dto.UpdateRecordTemplateRequest;
import com.mannschaft.app.chart.dto.UpdateSectionSettingsRequest;
import com.mannschaft.app.chart.service.ChartSettingsService;
import com.mannschaft.app.common.ApiResponse;
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
 * カルテ設定コントローラー。セクション設定・カスタムフィールド・カルテテンプレートAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/charts/settings")
@Tag(name = "カルテ設定", description = "F07.4 セクション設定・カスタムフィールド・テンプレート管理")
@RequiredArgsConstructor
public class ChartSettingsController {

    private final ChartSettingsService settingsService;

    // ========================
    // セクション設定 (20, 21)
    // ========================

    /**
     * 20. セクション設定取得
     * GET /api/v1/teams/{teamId}/charts/settings/sections
     */
    @GetMapping("/sections")
    @Operation(summary = "セクション設定取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<SectionSettingResponse>>> getSectionSettings(
            @PathVariable Long teamId) {
        List<SectionSettingResponse> response = settingsService.getSectionSettings(teamId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 21. セクション設定更新
     * PUT /api/v1/teams/{teamId}/charts/settings/sections
     */
    @PutMapping("/sections")
    @Operation(summary = "セクション設定更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<List<SectionSettingResponse>>> updateSectionSettings(
            @PathVariable Long teamId,
            @Valid @RequestBody UpdateSectionSettingsRequest request) {
        List<SectionSettingResponse> response = settingsService.updateSectionSettings(teamId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ========================
    // カスタムフィールド (22, 23, 24, 25)
    // ========================

    /**
     * 22. カスタムフィールド一覧
     * GET /api/v1/teams/{teamId}/charts/settings/custom-fields
     */
    @GetMapping("/custom-fields")
    @Operation(summary = "カスタムフィールド一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<CustomFieldResponse>>> listCustomFields(
            @PathVariable Long teamId) {
        List<CustomFieldResponse> response = settingsService.listCustomFields(teamId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 23. カスタムフィールド作成
     * POST /api/v1/teams/{teamId}/charts/settings/custom-fields
     */
    @PostMapping("/custom-fields")
    @Operation(summary = "カスタムフィールド作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<CustomFieldResponse>> createCustomField(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateCustomFieldRequest request) {
        CustomFieldResponse response = settingsService.createCustomField(teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 24. カスタムフィールド更新
     * PUT /api/v1/teams/{teamId}/charts/settings/custom-fields/{id}
     */
    @PutMapping("/custom-fields/{id}")
    @Operation(summary = "カスタムフィールド更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<CustomFieldResponse>> updateCustomField(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateCustomFieldRequest request) {
        CustomFieldResponse response = settingsService.updateCustomField(teamId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 25. カスタムフィールド無効化
     * DELETE /api/v1/teams/{teamId}/charts/settings/custom-fields/{id}
     */
    @DeleteMapping("/custom-fields/{id}")
    @Operation(summary = "カスタムフィールド無効化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "無効化成功")
    public ResponseEntity<Void> deactivateCustomField(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        settingsService.deactivateCustomField(teamId, id);
        return ResponseEntity.noContent().build();
    }

    // ========================
    // カルテテンプレート (28, 29, 30, 31)
    // ========================

    /**
     * 28. カルテテンプレート一覧
     * GET /api/v1/teams/{teamId}/charts/settings/record-templates
     */
    @GetMapping("/record-templates")
    @Operation(summary = "カルテテンプレート一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<RecordTemplateResponse>>> listRecordTemplates(
            @PathVariable Long teamId) {
        List<RecordTemplateResponse> response = settingsService.listRecordTemplates(teamId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 29. カルテテンプレート作成
     * POST /api/v1/teams/{teamId}/charts/settings/record-templates
     */
    @PostMapping("/record-templates")
    @Operation(summary = "カルテテンプレート作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<RecordTemplateResponse>> createRecordTemplate(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateRecordTemplateRequest request) {
        RecordTemplateResponse response = settingsService.createRecordTemplate(teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 30. カルテテンプレート更新
     * PUT /api/v1/teams/{teamId}/charts/settings/record-templates/{id}
     */
    @PutMapping("/record-templates/{id}")
    @Operation(summary = "カルテテンプレート更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<RecordTemplateResponse>> updateRecordTemplate(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateRecordTemplateRequest request) {
        RecordTemplateResponse response = settingsService.updateRecordTemplate(teamId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 31. カルテテンプレート削除（物理削除）
     * DELETE /api/v1/teams/{teamId}/charts/settings/record-templates/{id}
     */
    @DeleteMapping("/record-templates/{id}")
    @Operation(summary = "カルテテンプレート削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteRecordTemplate(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        settingsService.deleteRecordTemplate(teamId, id);
        return ResponseEntity.noContent().build();
    }
}
