package com.mannschaft.app.forms.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.forms.dto.FormSubmissionResponse;
import com.mannschaft.app.forms.service.FormSubmissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * フォーム提出管理コントローラー。管理者向けの提出一覧・承認・却下・差し戻しAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/form-templates/{templateId}/submissions")
@Tag(name = "フォーム提出管理", description = "F05.7 管理者向け提出管理")
@RequiredArgsConstructor
public class FormSubmissionAdminController {

    private final FormSubmissionService submissionService;

    /**
     * テンプレートに紐付く提出一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "テンプレートの提出一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<FormSubmissionResponse>> listSubmissions(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long templateId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<FormSubmissionResponse> result = submissionService.listSubmissionsByTemplate(
                templateId, status, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 提出を承認する。
     */
    @PostMapping("/{submissionId}/approve")
    @Operation(summary = "提出承認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "承認成功")
    public ResponseEntity<ApiResponse<FormSubmissionResponse>> approveSubmission(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long templateId,
            @PathVariable Long submissionId) {
        FormSubmissionResponse response = submissionService.approveSubmission(submissionId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 提出を却下する。
     */
    @PostMapping("/{submissionId}/reject")
    @Operation(summary = "提出却下")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "却下成功")
    public ResponseEntity<ApiResponse<FormSubmissionResponse>> rejectSubmission(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long templateId,
            @PathVariable Long submissionId) {
        FormSubmissionResponse response = submissionService.rejectSubmission(submissionId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 提出を差し戻す。
     */
    @PostMapping("/{submissionId}/return")
    @Operation(summary = "提出差し戻し")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "差し戻し成功")
    public ResponseEntity<ApiResponse<FormSubmissionResponse>> returnSubmission(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long templateId,
            @PathVariable Long submissionId) {
        FormSubmissionResponse response = submissionService.returnSubmission(submissionId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
