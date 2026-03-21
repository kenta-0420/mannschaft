package com.mannschaft.app.forms.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.forms.dto.CreateFormSubmissionRequest;
import com.mannschaft.app.forms.dto.FormSubmissionResponse;
import com.mannschaft.app.forms.dto.UpdateFormSubmissionRequest;
import com.mannschaft.app.forms.service.FormSubmissionService;
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
 * フォーム提出コントローラー。提出のCRUD・ステータス遷移APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/form-submissions")
@Tag(name = "フォーム提出", description = "F05.7 フォーム提出CRUD・承認管理")
@RequiredArgsConstructor
public class FormSubmissionController {

    private final FormSubmissionService submissionService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * ユーザーの提出一覧を取得する。
     */
    @GetMapping("/my")
    @Operation(summary = "自分の提出一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<FormSubmissionResponse>> listMySubmissions(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<FormSubmissionResponse> result = submissionService.listMySubmissions(
                getCurrentUserId(), scopeType, scopeId, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 提出詳細を取得する。
     */
    @GetMapping("/{submissionId}")
    @Operation(summary = "提出詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<FormSubmissionResponse>> getSubmission(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long submissionId) {
        FormSubmissionResponse response = submissionService.getSubmission(submissionId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 提出を作成する。
     */
    @PostMapping
    @Operation(summary = "提出作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<FormSubmissionResponse>> createSubmission(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @Valid @RequestBody CreateFormSubmissionRequest request) {
        FormSubmissionResponse response = submissionService.createSubmission(
                scopeType, scopeId, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 提出を更新する。
     */
    @PutMapping("/{submissionId}")
    @Operation(summary = "提出更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<FormSubmissionResponse>> updateSubmission(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long submissionId,
            @Valid @RequestBody UpdateFormSubmissionRequest request) {
        FormSubmissionResponse response = submissionService.updateSubmission(
                submissionId, getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 提出を削除する。
     */
    @DeleteMapping("/{submissionId}")
    @Operation(summary = "提出削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteSubmission(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long submissionId) {
        submissionService.deleteSubmission(submissionId, getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
