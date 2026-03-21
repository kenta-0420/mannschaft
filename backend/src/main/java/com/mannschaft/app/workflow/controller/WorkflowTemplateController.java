package com.mannschaft.app.workflow.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.workflow.dto.CreateWorkflowTemplateRequest;
import com.mannschaft.app.workflow.dto.UpdateWorkflowTemplateRequest;
import com.mannschaft.app.workflow.dto.WorkflowTemplateResponse;
import com.mannschaft.app.workflow.service.WorkflowTemplateService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ワークフローテンプレートコントローラー。テンプレートのCRUD・有効化/無効化APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/workflow-templates")
@Tag(name = "ワークフローテンプレート", description = "F05.6 ワークフローテンプレート管理")
@RequiredArgsConstructor
public class WorkflowTemplateController {

    private final WorkflowTemplateService templateService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * テンプレート一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "テンプレート一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<WorkflowTemplateResponse>> listTemplates(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<WorkflowTemplateResponse> result = templateService.listTemplates(
                scopeType, scopeId, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * テンプレート詳細を取得する。
     */
    @GetMapping("/{templateId}")
    @Operation(summary = "テンプレート詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<WorkflowTemplateResponse>> getTemplate(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long templateId) {
        WorkflowTemplateResponse response = templateService.getTemplate(scopeType, scopeId, templateId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * テンプレートを作成する。
     */
    @PostMapping
    @Operation(summary = "テンプレート作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<WorkflowTemplateResponse>> createTemplate(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @Valid @RequestBody CreateWorkflowTemplateRequest request) {
        WorkflowTemplateResponse response = templateService.createTemplate(
                scopeType, scopeId, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * テンプレートを更新する。
     */
    @PutMapping("/{templateId}")
    @Operation(summary = "テンプレート更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<WorkflowTemplateResponse>> updateTemplate(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long templateId,
            @Valid @RequestBody UpdateWorkflowTemplateRequest request) {
        WorkflowTemplateResponse response = templateService.updateTemplate(scopeType, scopeId, templateId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * テンプレートを削除する。
     */
    @DeleteMapping("/{templateId}")
    @Operation(summary = "テンプレート削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteTemplate(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long templateId) {
        templateService.deleteTemplate(scopeType, scopeId, templateId);
        return ResponseEntity.noContent().build();
    }
}
