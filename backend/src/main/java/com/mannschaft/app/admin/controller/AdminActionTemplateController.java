package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.ActionTemplateResponse;
import com.mannschaft.app.admin.dto.CreateActionTemplateRequest;
import com.mannschaft.app.admin.dto.UpdateActionTemplateRequest;
import com.mannschaft.app.admin.service.AdminActionTemplateService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 管理者アクションテンプレートコントローラー。
 */
@RestController
@RequestMapping("/api/v1/admin/action-templates")
@Tag(name = "管理 - アクションテンプレート", description = "F10.1 アクションテンプレート管理API")
@RequiredArgsConstructor
public class AdminActionTemplateController {

    private final AdminActionTemplateService templateService;


    /**
     * テンプレート一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "アクションテンプレート一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ActionTemplateResponse>>> getTemplates(
            @RequestParam(required = false) String actionType) {
        List<ActionTemplateResponse> templates;
        if (actionType != null && !actionType.isBlank()) {
            templates = templateService.getTemplatesByActionType(actionType);
        } else {
            templates = templateService.getAllTemplates();
        }
        return ResponseEntity.ok(ApiResponse.of(templates));
    }

    /**
     * テンプレートを作成する。
     */
    @PostMapping
    @Operation(summary = "アクションテンプレート作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ActionTemplateResponse>> createTemplate(
            @Valid @RequestBody CreateActionTemplateRequest request) {
        ActionTemplateResponse response = templateService.createTemplate(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * テンプレートを更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "アクションテンプレート更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ActionTemplateResponse>> updateTemplate(
            @PathVariable Long id,
            @Valid @RequestBody UpdateActionTemplateRequest request) {
        ActionTemplateResponse response = templateService.updateTemplate(id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * テンプレートを削除する（論理削除）。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "アクションテンプレート削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteTemplate(@PathVariable Long id) {
        templateService.deleteTemplate(id);
        return ResponseEntity.noContent().build();
    }
}
