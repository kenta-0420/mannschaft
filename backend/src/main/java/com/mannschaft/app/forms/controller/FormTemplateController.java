package com.mannschaft.app.forms.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.forms.dto.CreateFormTemplateRequest;
import com.mannschaft.app.forms.dto.FormTemplateResponse;
import com.mannschaft.app.forms.dto.UpdateFormTemplateRequest;
import com.mannschaft.app.forms.service.FormTemplateService;
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
import com.mannschaft.app.common.SecurityUtils;

/**
 * フォームテンプレートコントローラー。テンプレートのCRUD・ステータス遷移APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/form-templates")
@Tag(name = "フォームテンプレート", description = "F05.7 書類テンプレート・フォームビルダー")
@RequiredArgsConstructor
public class FormTemplateController {

    private final FormTemplateService templateService;


    /**
     * テンプレート一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "テンプレート一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<FormTemplateResponse>> listTemplates(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<FormTemplateResponse> result = templateService.listTemplates(
                scopeType, scopeId, status, PageRequest.of(page, size));
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
    public ResponseEntity<ApiResponse<FormTemplateResponse>> getTemplate(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long templateId) {
        FormTemplateResponse response = templateService.getTemplate(scopeType, scopeId, templateId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * テンプレートを作成する。
     */
    @PostMapping
    @Operation(summary = "テンプレート作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<FormTemplateResponse>> createTemplate(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @Valid @RequestBody CreateFormTemplateRequest request) {
        FormTemplateResponse response = templateService.createTemplate(
                scopeType, scopeId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * テンプレートを更新する。
     */
    @PutMapping("/{templateId}")
    @Operation(summary = "テンプレート更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<FormTemplateResponse>> updateTemplate(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long templateId,
            @Valid @RequestBody UpdateFormTemplateRequest request) {
        FormTemplateResponse response = templateService.updateTemplate(
                scopeType, scopeId, templateId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * テンプレートを公開する。
     */
    @PostMapping("/{templateId}/publish")
    @Operation(summary = "テンプレート公開")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "公開成功")
    public ResponseEntity<ApiResponse<FormTemplateResponse>> publishTemplate(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long templateId) {
        FormTemplateResponse response = templateService.publishTemplate(scopeType, scopeId, templateId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * テンプレートを閉鎖する。
     */
    @PostMapping("/{templateId}/close")
    @Operation(summary = "テンプレート閉鎖")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "閉鎖成功")
    public ResponseEntity<ApiResponse<FormTemplateResponse>> closeTemplate(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long templateId) {
        FormTemplateResponse response = templateService.closeTemplate(scopeType, scopeId, templateId);
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
