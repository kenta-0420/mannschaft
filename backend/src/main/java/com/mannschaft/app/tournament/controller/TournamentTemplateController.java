package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.tournament.dto.CreateTemplateRequest;
import com.mannschaft.app.tournament.dto.PresetResponse;
import com.mannschaft.app.tournament.dto.TemplateResponse;
import com.mannschaft.app.tournament.dto.UpdateTemplateRequest;
import com.mannschaft.app.tournament.service.SystemPresetService;
import com.mannschaft.app.tournament.service.TournamentTemplateService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/**
 * テンプレート管理コントローラー（組織ADMIN）。
 * 7 endpoints: GET list, POST create, POST clone, GET detail, PATCH update, DELETE, GET public presets
 */
@RestController
@Tag(name = "大会テンプレート管理", description = "F08.7 テンプレートCRUD")
@RequiredArgsConstructor
public class TournamentTemplateController {

    private final TournamentTemplateService templateService;
    private final SystemPresetService presetService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    @GetMapping("/api/v1/organizations/{orgId}/tournament-templates")
    @Operation(summary = "テンプレート一覧")
    public ResponseEntity<PagedResponse<TemplateResponse>> listTemplates(
            @PathVariable Long orgId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<TemplateResponse> result = templateService.listTemplates(orgId, PageRequest.of(page, size));
        return ResponseEntity.ok(PagedResponse.of(result.getContent(),
                new PagedResponse.PageMeta(result.getTotalElements(), page, size, result.getTotalPages())));
    }

    @PostMapping("/api/v1/organizations/{orgId}/tournament-templates")
    @Operation(summary = "テンプレート作成（ゼロから）")
    public ResponseEntity<ApiResponse<TemplateResponse>> createTemplate(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(templateService.createTemplate(orgId, getCurrentUserId(), request)));
    }

    @PostMapping("/api/v1/organizations/{orgId}/tournament-templates/clone/{presetId}")
    @Operation(summary = "プリセットから複製して作成")
    public ResponseEntity<ApiResponse<TemplateResponse>> cloneFromPreset(
            @PathVariable Long orgId,
            @PathVariable Long presetId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(templateService.cloneFromPreset(orgId, getCurrentUserId(), presetId)));
    }

    @GetMapping("/api/v1/organizations/{orgId}/tournament-templates/{templateId}")
    @Operation(summary = "テンプレート詳細")
    public ResponseEntity<ApiResponse<TemplateResponse>> getTemplate(
            @PathVariable Long orgId,
            @PathVariable Long templateId) {
        return ResponseEntity.ok(ApiResponse.of(templateService.getTemplate(orgId, templateId)));
    }

    @PatchMapping("/api/v1/organizations/{orgId}/tournament-templates/{templateId}")
    @Operation(summary = "テンプレート更新")
    public ResponseEntity<ApiResponse<TemplateResponse>> updateTemplate(
            @PathVariable Long orgId,
            @PathVariable Long templateId,
            @Valid @RequestBody UpdateTemplateRequest request) {
        return ResponseEntity.ok(ApiResponse.of(templateService.updateTemplate(orgId, templateId, request)));
    }

    @DeleteMapping("/api/v1/organizations/{orgId}/tournament-templates/{templateId}")
    @Operation(summary = "テンプレート論理削除")
    public ResponseEntity<Void> deleteTemplate(
            @PathVariable Long orgId,
            @PathVariable Long templateId) {
        templateService.deleteTemplate(templateId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/tournament-presets")
    @Operation(summary = "公開プリセット一覧")
    public ResponseEntity<PagedResponse<PresetResponse>> listPublicPresets(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PresetResponse> result = presetService.listPresets(PageRequest.of(page, size));
        return ResponseEntity.ok(PagedResponse.of(result.getContent(),
                new PagedResponse.PageMeta(result.getTotalElements(), page, size, result.getTotalPages())));
    }
}
