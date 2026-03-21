package com.mannschaft.app.workflow.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.workflow.dto.CreateWorkflowRequestRequest;
import com.mannschaft.app.workflow.dto.UpdateWorkflowRequestRequest;
import com.mannschaft.app.workflow.dto.WorkflowRequestResponse;
import com.mannschaft.app.workflow.service.WorkflowRequestService;
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
 * ワークフロー申請コントローラー。申請のCRUD・提出・取り下げAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/workflow-requests")
@Tag(name = "ワークフロー申請", description = "F05.6 ワークフロー申請管理")
@RequiredArgsConstructor
public class WorkflowRequestController {

    private final WorkflowRequestService requestService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 申請一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "申請一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<WorkflowRequestResponse>> listRequests(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<WorkflowRequestResponse> result = requestService.listRequests(
                scopeType, scopeId, status, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 申請詳細を取得する。
     */
    @GetMapping("/{requestId}")
    @Operation(summary = "申請詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<WorkflowRequestResponse>> getRequest(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long requestId) {
        WorkflowRequestResponse response = requestService.getRequest(scopeType, scopeId, requestId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 申請を作成する（下書き）。
     */
    @PostMapping
    @Operation(summary = "申請作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<WorkflowRequestResponse>> createRequest(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @Valid @RequestBody CreateWorkflowRequestRequest request) {
        WorkflowRequestResponse response = requestService.createRequest(
                scopeType, scopeId, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 申請を更新する（下書きのみ）。
     */
    @PutMapping("/{requestId}")
    @Operation(summary = "申請更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<WorkflowRequestResponse>> updateRequest(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long requestId,
            @Valid @RequestBody UpdateWorkflowRequestRequest request) {
        WorkflowRequestResponse response = requestService.updateRequest(scopeType, scopeId, requestId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 申請を提出する。
     */
    @PostMapping("/{requestId}/submit")
    @Operation(summary = "申請提出")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "提出成功")
    public ResponseEntity<ApiResponse<WorkflowRequestResponse>> submitRequest(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long requestId) {
        WorkflowRequestResponse response = requestService.submitRequest(scopeType, scopeId, requestId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 申請を取り下げる。
     */
    @PostMapping("/{requestId}/withdraw")
    @Operation(summary = "申請取り下げ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取り下げ成功")
    public ResponseEntity<ApiResponse<WorkflowRequestResponse>> withdrawRequest(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long requestId) {
        WorkflowRequestResponse response = requestService.withdrawRequest(scopeType, scopeId, requestId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 申請を削除する。
     */
    @DeleteMapping("/{requestId}")
    @Operation(summary = "申請削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteRequest(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long requestId) {
        requestService.deleteRequest(scopeType, scopeId, requestId);
        return ResponseEntity.noContent().build();
    }
}
