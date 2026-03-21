package com.mannschaft.app.workflow.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.workflow.dto.WorkflowTemplateResponse;
import com.mannschaft.app.workflow.service.WorkflowTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ワークフローテンプレートステータスコントローラー。有効化/無効化APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/workflow-templates/{templateId}")
@Tag(name = "ワークフローテンプレート", description = "F05.6 テンプレート有効化/無効化")
@RequiredArgsConstructor
public class WorkflowTemplateStatusController {

    private final WorkflowTemplateService templateService;

    /**
     * テンプレートを有効化する。
     */
    @PostMapping("/activate")
    @Operation(summary = "テンプレート有効化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "有効化成功")
    public ResponseEntity<ApiResponse<WorkflowTemplateResponse>> activateTemplate(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long templateId) {
        WorkflowTemplateResponse response = templateService.activateTemplate(scopeType, scopeId, templateId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * テンプレートを無効化する。
     */
    @PostMapping("/deactivate")
    @Operation(summary = "テンプレート無効化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "無効化成功")
    public ResponseEntity<ApiResponse<WorkflowTemplateResponse>> deactivateTemplate(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long templateId) {
        WorkflowTemplateResponse response = templateService.deactivateTemplate(scopeType, scopeId, templateId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
