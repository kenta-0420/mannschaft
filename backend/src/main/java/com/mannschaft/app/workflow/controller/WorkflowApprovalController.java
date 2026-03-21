package com.mannschaft.app.workflow.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.workflow.dto.ApprovalDecisionRequest;
import com.mannschaft.app.workflow.dto.WorkflowRequestResponse;
import com.mannschaft.app.workflow.service.WorkflowApprovalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ワークフロー承認コントローラー。承認・却下APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/workflow-requests/{requestId}")
@Tag(name = "ワークフロー承認", description = "F05.6 承認・却下判断")
@RequiredArgsConstructor
public class WorkflowApprovalController {

    private final WorkflowApprovalService approvalService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 承認判断を行う。
     */
    @PostMapping("/decide")
    @Operation(summary = "承認判断")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "判断成功")
    public ResponseEntity<ApiResponse<WorkflowRequestResponse>> decide(
            @PathVariable Long requestId,
            @Valid @RequestBody ApprovalDecisionRequest request) {
        WorkflowRequestResponse response = approvalService.decide(requestId, getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
